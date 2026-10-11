import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoVuDownload;
import net.osmand.plus.roadcrew.tacho.RoadCrewTachoDdd;

/** Optional local replay; input holds private payloads and must never be committed. */
public class VuCaptureReplay implements RoadCrewTachoVuDownload.Channel {
    final DataInputStream in;
    int messages;
    VuCaptureReplay(Path input) throws IOException { in = new DataInputStream(Files.newInputStream(input)); }
    byte[] next(boolean rx) throws IOException {
        if (in.readUnsignedByte() != (rx ? 1 : 0)) throw new IOException("Capture direction mismatch at " + messages);
        int size = in.readUnsignedShort();
        byte[] data = in.readNBytes(size);
        if (data.length != size) throw new IOException("Capture truncated");
        messages++;
        return data;
    }
    public void send(byte[] bytes) throws IOException {
        if (!Arrays.equals(bytes, next(false))) throw new IOException("Command differs at " + messages);
    }
    public byte[] receive(long timeout) throws IOException {
        // Exercise the production BLE reassembler as well, not just the Appendix 7 parser.
        var assembly = new RoadCrewTachoVuDownload.Assembler();
        byte[] complete = null;
        for (byte[] part : RoadCrewTachoVuDownload.segment(next(true), 240)) complete = assembly.accept(part);
        if (complete == null) throw new IOException("Incomplete frame");
        return complete;
    }
    public static void main(String[] args) throws Exception {
        var replay = new VuCaptureReplay(Path.of(args[0]));
        try (var ignored = replay.in) {
            byte[] result = RoadCrewTachoVuDownload.download(replay, (bytes, parts) -> {});
            if (replay.in.read() != -1) throw new IOException("Unconsumed capture frames");
            RoadCrewTachoDdd.validateAndGetCardNumber(result);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result));
            if (!hash.equals(args[1])) throw new IOException("Reconstructed file differs");
            System.out.println("PASS: " + replay.messages + " frames, " + result.length + " DDD bytes; hash matches; structural check passed");
        }
    }
}
