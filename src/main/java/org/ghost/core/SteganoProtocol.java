package org.ghost.core;

import java.awt.image.BufferedImage;

public interface SteganoProtocol {

    public void cloak(BufferedImage image, byte[] data);

    public byte[] reveal(BufferedImage image);
}
