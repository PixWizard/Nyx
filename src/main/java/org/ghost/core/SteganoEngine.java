package org.ghost.core;

import java.awt.image.BufferedImage;
//import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class SteganoEngine {

    //  HIDING OF THE DATA -->
    public void cloak(BufferedImage image, byte[] data) {

        int width = image.getWidth();
        int height = image.getHeight();

        int byteIndex = 0;
        int bitIndex = 0;

//      Storing the size of the data in the first 4 byte of the file (32bits for the dataLength)- >I15(RES)
        byte[] header = ByteBuffer.allocate(4).putInt(data.length).array();
        byte[] totalData = new byte[header.length + data.length]; //stored in the form of bytes

//      TOTAL LEN = DATA LEN + 4 Bytes HEADER FOR DATA LENGTH
        System.arraycopy(header, 0, totalData, 0, header.length);
        System.arraycopy(data, 0, totalData, header.length, data.length);

//      Getting all the pixels into a Buffer for faster modification.
        int[] pixelBuffer = new int[width * height];
        image.getRGB(0, 0, width, height, pixelBuffer, 0, width);

//      For LSB's of RGB.
        int[] shifts = {16,8,0};

        for (int i = 0; i < pixelBuffer.length; ++i) {

            int pixel = pixelBuffer[i]; //Extracting the pixel

//          Bit Modification -->
            pixel = pixel & 0xFFFEFEFE; //Masking the required bits to 0.

            for (int shift : shifts) {
                int bitToHide = (totalData[byteIndex] >> (7 - bitIndex)) & 1;
                pixel = pixel | (bitToHide << shift);

                bitIndex++;
                if(bitIndex == 8) {
                    bitIndex = 0;
                    byteIndex++;
                }
                if(byteIndex >= totalData.length) break;
            }

            pixelBuffer[i] = pixel; //Rewriting the updated pixel.

            if (byteIndex >= totalData.length) break;

        }
        //Saving the pixel at the end--> v3
        image.setRGB(0, 0, width, height, pixelBuffer, 0, width);
    }

    //  RETRIEVAL OF DATA -->
    public byte[] reveal(BufferedImage image) {

        int width = image.getWidth();
        int height = image.getHeight();

        //For storing the length of the data.
        byte[] header = new byte[4];
        int headerLength = 4; // 4 bytes allocated for storing the data.
        int headerIndex = 0;

        byte[] data = null; //Not yet initialized.
        int dataIndex = 0;
        int dataLength = -1;

        int currentByte = 0;
        int bitCount = 0;

        int[] shifts = {16,8,0}; //For accessing the bitVal's of RGB.

        int[] pixelBuffer = new int[height * width];
        //Loading the whole data into the matrix.
        image.getRGB(0, 0, width, height, pixelBuffer, 0, width);

//      Loop for getting the header and traversing the bits of the actual data.
        for(int i=0; i<pixelBuffer.length; ++i) {

            int pixel = pixelBuffer[i];

            for(int shift: shifts) {
                int bit = (pixel >> shift) & 1;
                currentByte = (currentByte << 1) | bit;
                bitCount++;

                if(bitCount == 8) {
                    if(headerIndex < headerLength) {
                        header[headerIndex++] = (byte) currentByte;
                        if(headerIndex == 4) {
                            dataLength = ByteBuffer.wrap(header).getInt();
                            data = new byte[dataLength];
                        }
                    }

                    else if(dataIndex < dataLength) {
                        data[dataIndex++] = (byte) currentByte;
                        if(dataIndex == dataLength) return data;
                    }

                    bitCount = 0;
                    currentByte = 0;
                }
            }
        }
        return data;
    }
}
