# 👻 Nyx
**Simple. Secure. Invisible.**

Nyx is a lightweight, high-performance steganography tool built in Java. It allows you to hide any file—whether it's a secret PDF, a private image, or a simple text file—inside a standard image carrier without changing how the image looks.

---

### ✨ What makes Nyx different?

* **Universal Support:** It doesn't care about file types. If it has bits, Nyx can hide it (PDF, PNG, JPG, TXT, etc.).
* **Triple-Channel Logic:** Most tools only use one color channel. Nyx uses Red, Green, and Blue simultaneously to maximize storage space.
* **Built-in Security:** Your data is automatically encrypted with **AES-256** before it's hidden. Even if someone finds the data, they can't read it without your password.
* **Pixel-Perfect:** Designed to keep the "Ghost" image looking identical to the original, even under close inspection.

---

### 🛠️ How it works (The Tech)

The project follows clean **Object-Oriented Programming (OOP)** principles, making it easy to read and extend:

* **`SteganoProtocol`**: A simple interface that defines the "Cloak" and "Reveal" actions.
* **`AbstractSteganoEngine`**: The parent class that handles the heavy lifting of image processing.
* **`SteganoEngine`**: The core logic that performs the bit-level magic.
* **`CipherManager`**: Your security guard, handling all the encryption and decryption.

---

### 🚀 Quick Start

**To Hide Data:**
```java
SteganoProtocol ghost = new SteganoEngine();
ghost.cloak(carrierImage, secretFileBytes);
```

**To Reveal Data:**
```java
byte[] hiddenData = ghost.reveal(ghostImage);
byte[] originalFile = CipherManager.decrypt(hiddenData, "your-password");
```
