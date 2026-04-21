package org.ghost.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * Main application entry point for the Ghost Steganography JavaFX UI.
 */
public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("main_view.fxml"));
            Parent root = loader.load();
            
            Scene scene = new Scene(root);
            
            primaryStage.setTitle("Nyx-Stegano | Digital Ghost");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(1000);
            primaryStage.setMinHeight(650);
            primaryStage.show();
            
        } catch (IOException e) {
            System.err.println("Failed to load FXML: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}