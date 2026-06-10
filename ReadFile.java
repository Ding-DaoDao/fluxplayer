import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;

public class ReadFile {
    public static void main(String[] args) throws Exception {
        Path p = Paths.get(args[0]);
        byte[] bytes = Files.readAllBytes(p);
        System.out.println("File size: " + bytes.length + " bytes");
        System.out.println("First 20 bytes (hex):");
        for (int i = 0; i < Math.min(20, bytes.length); i++) {
            System.out.printf("%02X ", bytes[i] & 0xFF);
        }
        System.out.println();
        
        // Try UTF-8
        try {
            String s = new String(bytes, StandardCharsets.UTF_8);
            System.out.println("UTF-8 first 500 chars:");
            System.out.println(s.substring(0, Math.min(500, s.length())));
        } catch (Exception e) {
            System.out.println("UTF-8 failed: " + e.getMessage());
        }
    }
}
