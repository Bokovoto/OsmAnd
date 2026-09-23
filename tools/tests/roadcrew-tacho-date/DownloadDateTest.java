import net.osmand.plus.roadcrew.tacho.RoadCrewTachoDownloadDate;
import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;

public class DownloadDateTest {
    static final long DATE = 0x6ABCDE01L;
    static final String[] COMMANDS = {"00a4040c06ff544143484f", "00a4020c020501",
        "00b0000001", "00a4020c02050e", "00b0000004", "00d60000046abcde01", "00b0000004"};
    static final String[] REPLIES = {"9000", "9000", "019000", "9000", "000000019000", "9000", "6abcde019000"};
    static int cases;

    static class Card implements RoadCrewTachoDownloadDate.Channel {
        int calls, writes, changeAt = -1;
        String replacement;
        boolean audit;
        public byte[] exchange(byte[] command) throws IOException {
            int step = calls++;
            check(step < COMMANDS.length, "unexpected retry");
            check(HexFormat.of().formatHex(command).equals(COMMANDS[step]), "wrong APDU at " + step);
            if ((command[1] & 255) == 0xD6) {
                check(audit, "write before durable audit");
                writes++;
            }
            if (step == changeAt && replacement == null) throw new IOException("USB disconnected");
            return HexFormat.of().parseHex(step == changeAt ? replacement : REPLIES[step]);
        }
    }

    static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    static void write(Card card) throws IOException {
        long old = RoadCrewTachoDownloadDate.writeTestDate(card, DATE, (before, after) -> {
            check(before == 1 && after == DATE, "audit values");
            card.audit = true;
        });
        check(old == 1, "old date");
    }

    public static void main(String[] args) throws Exception {
        Card read = new Card();
        check(RoadCrewTachoDownloadDate.read(read) == 1 && read.calls == 5 && read.writes == 0, "read only");
        cases++;
        Card success = new Card();
        write(success);
        check(success.calls == 7 && success.writes == 1, "single verified write");
        cases++;
        for (int step = 0; step < 7; step++) {
            for (String invalid : new String[]{"6982", "", null}) {
                Card card = new Card(); card.changeAt = step; card.replacement = invalid;
                try { write(card); throw new AssertionError("accepted failed step " + step); }
                catch (IOException expected) {
                    check(card.calls == step + 1, "continued after failure");
                    check(card.writes == (step < 5 ? 0 : 1), "unsafe write or retry");
                    if (step >= 5) check(expected.getMessage().contains("NOT verified"), "ambiguous write hidden");
                }
                cases++;
            }
        }
        for (int step : new int[]{2, 4, 6}) {
            for (String invalid : step == 2 ? new String[]{"029000", "9000", "01009000"}
                    : new String[]{"0000009000", "00000000019000", "ffffffff9000"}) {
                Card card = new Card(); card.changeAt = step; card.replacement = invalid;
                try { write(card); throw new AssertionError("accepted invalid contents"); }
                catch (IOException expected) { check(card.writes == (step < 5 ? 0 : 1), "bad data gate"); }
                cases++;
            }
        }
        for (long bad : new long[]{-1, 0, 0x100000000L}) {
            Card card = new Card();
            try { RoadCrewTachoDownloadDate.writeTestDate(card, bad, (a,b) -> {}); throw new AssertionError(); }
            catch (IOException expected) { check(card.calls == 0, "invalid clock accessed card"); }
            cases++;
        }
        for (long old : new long[]{DATE, DATE + 1}) {
            Card card = new Card(); card.changeAt = 4; card.replacement = String.format("%08x9000", old);
            try { write(card); throw new AssertionError(); }
            catch (IOException expected) { check(card.writes == 0, "date moved backwards or repeated"); }
            cases++;
        }
        Card auditFail = new Card();
        try { RoadCrewTachoDownloadDate.writeTestDate(auditFail, DATE, (a,b) -> { throw new IOException("disk full"); }); throw new AssertionError(); }
        catch (IOException expected) { check(auditFail.writes == 0, "write despite failed audit"); }
        cases++;
        System.out.println(cases + " protocol cases passed (simulated card, not hardware)");
    }
}
