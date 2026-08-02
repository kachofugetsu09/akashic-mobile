import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class VerifyMobileWebArchive extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getArchiveFile();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getDigestFile();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSourceFile();

    @TaskAction
    public void verify() throws IOException, NoSuchAlgorithmException {
        File archive = getArchiveFile().get().getAsFile();
        File digest = getDigestFile().get().getAsFile();
        File source = getSourceFile().get().getAsFile();
        String[] digestParts = Files.readString(digest.toPath()).trim().split("\\s+");
        if (digestParts.length != 2 || !digestParts[1].equals(archive.getName())) {
            throw new IllegalStateException("Mobile WebUI digest must name " + archive.getName());
        }

        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(archive.toPath())) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                messageDigest.update(buffer, 0, count);
            }
        }
        String actualDigest = HexFormat.of().formatHex(messageDigest.digest());
        if (!actualDigest.equals(digestParts[0])) {
            throw new IllegalStateException(
                "Mobile WebUI SHA-256 mismatch: expected " + digestParts[0] + ", got " + actualDigest
            );
        }

        try (var zip = new ZipFile(archive)) {
            if (zip.getEntry("mobile.html") == null) {
                throw new IllegalStateException("Mobile WebUI archive has no mobile.html");
            }
            var manifestEntry = zip.getEntry("akashic-webui-manifest.json");
            if (manifestEntry == null) {
                throw new IllegalStateException("Mobile WebUI archive has no source manifest");
            }
            String embeddedManifest;
            try (var input = zip.getInputStream(manifestEntry)) {
                embeddedManifest = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!embeddedManifest.equals(Files.readString(source.toPath()))) {
                throw new IllegalStateException(
                    "Mobile WebUI source manifest does not match clients/android/mobile-web/source.json"
                );
            }
        }
    }
}
