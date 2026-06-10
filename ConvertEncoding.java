import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.stream.*;

public class ConvertEncoding {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".");
        String[] exts = {"kts", "toml", "properties", "gitignore", "yaml", "xml"};
        
        Files.walk(root)
            .filter(Files::isRegularFile)
            .filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                for (String ext : exts) {
                    if (name.endsWith("." + ext)) return true;
                }
                return name.equals(".gitignore");
            })
            .forEach(p -> {
                try {
                    byte[] bytes = Files.readAllBytes(p);
                    // Check if UTF-16 LE with BOM
                    if (bytes.length >= 2 && bytes[0] == (byte)0xFF && bytes[1] == (byte)0xFE) {
                        String content = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
                        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
                        System.out.println("Converted (UTF-16LE->UTF8): " + p);
                    } else if (bytes.length >= 2 && bytes[0] == (byte)0xFE && bytes[1] == (byte)0xFF) {
                        String content = new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
                        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
                        System.out.println("Converted (UTF-16BE->UTF8): " + p);
                    }
                } catch (Exception e) {
                    System.err.println("Failed: " + p + " - " + e.getMessage());
                }
            });
        System.out.println("Done.");
    }
}
