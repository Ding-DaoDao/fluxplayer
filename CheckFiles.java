import java.io.*;
import java.nio.file.*;

public class CheckFiles {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".");
        String[] exts = {".kts", ".toml", ".properties", ".xml", ".kt"};
        
        Files.walk(root)
            .filter(Files::isRegularFile)
            .filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                for (String ext : exts) {
                    if (name.endsWith(ext)) return true;
                }
                return name.equals(".gitignore");
            })
            .forEach(p -> {
                try {
                    byte[] bytes = Files.readAllBytes(p);
                    boolean allZero = true;
                    for (byte b : bytes) {
                        if (b != 0) { allZero = false; break; }
                    }
                    String status = allZero ? "[EMPTY/NULL]" : "[OK " + bytes.length + " bytes]";
                    System.out.println(status + " " + p);
                } catch (Exception e) {
                    System.out.println("[ERROR] " + p + " - " + e.getMessage());
                }
            });
    }
}
