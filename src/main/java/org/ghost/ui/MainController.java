package org.ghost.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import org.ghost.core.SteganoEngine;
import org.ghost.core.SteganoProtocol;
import org.ghost.crypto.CipherManager;
import org.ghost.crypto.CipherProtocol;
import org.ghost.utils.ImageManager;
import org.ghost.utils.UnsplashClient;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class MainController {

    private static final byte GHOST_MAGIC = 0x47; // 'G' for Ghost

    @FXML private Button btnNavCloak, btnNavUncloak;
    @FXML private VBox cloakView, uncloakView;
    @FXML private Text txtSecretFile, txtCarrierImage, txtGhostImage, txtPreviewStatus, txtUncloakResult, txtUncloakDetail;
    @FXML private ImageView ivPreview;
    @FXML private PasswordField pfCloakKey, pfUncloakKey;
    @FXML private ProgressBar pbCloak;
    @FXML private VBox uncloakStatusBox;

    private File secretFile;
    private File carrierFile;
    private File ghostFile;
    private BufferedImage carrierImage;

    private final SteganoProtocol engine = new SteganoEngine();
    private final CipherProtocol cipher = new CipherManager();

    @FXML
    public void initialize() {
        showView(cloakView, btnNavCloak);
    }

    @FXML
    private void handleNavCloak() {
        showView(cloakView, btnNavCloak);
    }

    @FXML
    private void handleNavUncloak() {
        showView(uncloakView, btnNavUncloak);
    }

    private void showView(VBox view, Button navBtn) {
        cloakView.setVisible(false);
        uncloakView.setVisible(false);
        btnNavCloak.getStyleClass().remove("active");
        btnNavUncloak.getStyleClass().remove("active");

        view.setVisible(true);
        navBtn.getStyleClass().add("active");
    }

    @FXML
    private void selectSecretFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Secret File");
        File file = chooser.showOpenDialog(null);
        if (file != null) {
            this.secretFile = file;
            txtSecretFile.setText(file.getName());
        }
    }

    @FXML
    private void selectCarrierImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Carrier Image");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.bmp"));
        File file = chooser.showOpenDialog(null);
        if (file != null) {
            this.carrierFile = file;
            updateCarrierPreview(file);
        }
    }

    @FXML
    private void fetchUnsplash() {
        Task<BufferedImage> task = new Task<>() {
            @Override
            protected BufferedImage call() throws Exception {
                // Approximate 5MB worth of bits for a generic high-res image
                return UnsplashClient.fetchCarrierImage(40000000L, "temp_carrier.png");
            }
        };

        task.setOnSucceeded(e -> {
            carrierImage = task.getValue();
            this.carrierFile = new File("temp_carrier.png");
            updateCarrierPreview(this.carrierFile);
            showAlert(Alert.AlertType.INFORMATION, "Success", "Random HD Carrier fetched from Unsplash!");
        });

        task.setOnFailed(e -> showAlert(Alert.AlertType.ERROR, "Fetch Failed", task.getException().getMessage()));

        new Thread(task).start();
    }

    private void updateCarrierPreview(File file) {
        try {
            Image image = new Image(file.toURI().toString());
            ivPreview.setImage(image);
            txtCarrierImage.setText(file.getName());
            txtPreviewStatus.setVisible(false);
            
            // Load the image into memory for the engine
            carrierImage = ImageManager.loadAndPrepareCarrier(file.getAbsolutePath());
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Preview Error", e.getMessage());
        }
    }

    @FXML
    private void startCloaking() {
        if (secretFile == null || carrierImage == null) {
            showAlert(Alert.AlertType.WARNING, "Missing Data", "Please select both a secret file and a carrier image.");
            return;
        }

        String password = pfCloakKey.getText();
        if (password.isEmpty()) password = "GhostProtocol123";

        final String finalPwd = password;
        pbCloak.setVisible(true);
        pbCloak.setProgress(-1);

        Task<File> cloakTask = new Task<>() {
            @Override
            protected File call() throws Exception {
                byte[] fileBytes = Files.readAllBytes(secretFile.toPath());
                String extension = getExtension(secretFile.getName());
                
                // Metadata packing (Extension preservation)
                byte[] packet = packData(fileBytes, extension);
                
                long requiredBits = (long) (packet.length + 4) * 8;
                long capacity = (long) carrierImage.getWidth() * carrierImage.getHeight() * 3;
                
                if (requiredBits > capacity) {
                    throw new IOException("Carrier too small. Required: " + requiredBits + " bits. Capacity: " + capacity);
                }

                byte[] encrypted = cipher.encrypt(packet, finalPwd);
                engine.cloak(carrierImage, encrypted);

                File outputFile = new File("ghost_output.png");
                ImageManager.saveImage(carrierImage, outputFile.getAbsolutePath());
                return outputFile;
            }
        };

        cloakTask.setOnSucceeded(e -> {
            pbCloak.setVisible(false);
            showAlert(Alert.AlertType.INFORMATION, "Success", "File cloaked successfully into: " + cloakTask.getValue().getAbsolutePath());
        });

        cloakTask.setOnFailed(e -> {
            pbCloak.setVisible(false);
            showAlert(Alert.AlertType.ERROR, "Cloaking Failed", cloakTask.getException().getMessage());
        });

        new Thread(cloakTask).start();
    }

    @FXML
    private void selectGhostImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Ghost Image");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", "*.png"));
        File file = chooser.showOpenDialog(null);
        if (file != null) {
            this.ghostFile = file;
            txtGhostImage.setText(file.getName());
        }
    }

    @FXML
    private void startUncloaking() {
        if (ghostFile == null) {
            showAlert(Alert.AlertType.WARNING, "Missing Data", "Please select a Ghost image.");
            return;
        }

        String password = pfUncloakKey.getText();
        if (password.isEmpty()) password = "GhostProtocol123";

        final String finalPwd = password;
        uncloakStatusBox.setVisible(true);
        txtUncloakResult.setText("Processing...");
        txtUncloakDetail.setText("Extracting bits from image...");

        Task<Void> uncloakTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                BufferedImage ghost = ImageManager.loadImage(ghostFile.getAbsolutePath());
                byte[] revealed = engine.reveal(ghost);
                byte[] decrypted = cipher.decrypt(revealed, finalPwd);

                // Unpack metadata
                String extension = "";
                byte[] fileData = decrypted;

                if (decrypted != null && decrypted.length > 2 && decrypted[0] == GHOST_MAGIC) {
                    int extLen = decrypted[1] & 0xFF;
                    if (decrypted.length >= 2 + extLen) {
                        extension = new String(decrypted, 2, extLen, StandardCharsets.UTF_8);
                        fileData = Arrays.copyOfRange(decrypted, 2 + extLen, decrypted.length);
                    }
                }

                final byte[] finalData = fileData;
                final String finalExt = extension;

                Platform.runLater(() -> {
                    FileChooser saver = new FileChooser();
                    saver.setTitle("Save Restored File");
                    String defaultName = "restored_file" + (finalExt.isEmpty() ? ".bin" : finalExt);
                    saver.setInitialFileName(defaultName);
                    File saveDest = saver.showSaveDialog(null);

                    if (saveDest != null) {
                        try {
                            Files.write(saveDest.toPath(), finalData);
                            txtUncloakResult.setText("SUCCESS");
                            txtUncloakDetail.setText("Restored as: " + saveDest.getName());
                        } catch (IOException ex) {
                            showAlert(Alert.AlertType.ERROR, "Save Failed", ex.getMessage());
                        }
                    } else {
                        txtUncloakResult.setText("READY");
                        txtUncloakDetail.setText("Save cancelled.");
                    }
                });
                return null;
            }
        };

        uncloakTask.setOnFailed(e -> {
            txtUncloakResult.setText("FAILED");
            txtUncloakDetail.setText(uncloakTask.getException().getMessage());
            showAlert(Alert.AlertType.ERROR, "Extraction Failed", uncloakTask.getException().getMessage());
        });

        new Thread(uncloakTask).start();
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    private String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf(".");
        return (lastDot == -1) ? "" : fileName.substring(lastDot);
    }

    private byte[] packData(byte[] fileData, String extension) {
        byte[] extBytes = extension.getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[1 + 1 + extBytes.length + fileData.length];
        packet[0] = GHOST_MAGIC;
        packet[1] = (byte) extBytes.length;
        System.arraycopy(extBytes, 0, packet, 2, extBytes.length);
        System.arraycopy(fileData, 0, packet, 2 + extBytes.length, fileData.length);
        return packet;
    }
}
