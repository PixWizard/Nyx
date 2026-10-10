module org.ghost {
    requires javafx.controls;
    requires javafx.fxml;
    requires transitive java.desktop;
    requires java.net.http;
    requires org.bouncycastle.provider;

    opens org.ghost.ui to javafx.fxml;

    exports org.ghost.ui;
    exports org.ghost.core;
    exports org.ghost.utils;
}