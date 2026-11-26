package com.fnfmod.song;

/** A mod link must identify the same pack regardless of its local folder. */
public final class GameBananaModLinksChecks {
    public static void main(String[] args) {
        expect("643614", GameBananaModLinks.modId("https://gamebanana.com/mods/643614\n"));
        expect("643614", GameBananaModLinks.modId("https://www.gamebanana.com/mods/643614?x=1"));
        expect("", GameBananaModLinks.modId("https://example.com/mods/643614"));
        expect("", GameBananaModLinks.modId("https://gamebanana.com/games/8694"));
        require(ModDownloadLinks.parse("https://gamebanana.com/mods/643614") != null, "GameBanana link rejected");
        require(ModDownloadLinks.parse("https://drive.google.com/file/d/abc123/view") != null,
                "Google Drive link rejected");
        require(ModDownloadLinks.parse("https://www.mediafire.com/file/abc/mod.zip/file") != null,
                "MediaFire link rejected");
        require(ModDownloadLinks.parse("https://mega.nz/file/abcdefgh#some-key") != null,
                "MEGA link rejected");
        require(ModDownloadLinks.parse("https://mega.nz/#!abcdefgh!some-key") != null,
                "legacy MEGA link rejected");
        require(ModDownloadLinks.parse("http://drive.google.com/file/d/abc123/view") == null,
                "insecure link accepted");
        require(ModDownloadLinks.parse("https://gamebanana.com.evil.test/mods/643614") == null,
                "lookalike host accepted");
        require(ModDownloadLinks.parse("https://user:password@mega.nz/file/abcdefgh#key") == null,
                "credential-bearing link accepted");
        require(GameBananaInstaller.supportedArchiveName("pack.ZIP")
                && GameBananaInstaller.supportedArchiveName("pack.7z")
                && GameBananaInstaller.supportedArchiveName("pack.rar"), "archive extension rejected");
        require(new GameBananaInstaller.FileOption("1", "pack.7z", 1_500_000_000L, "")
                .installable(), "1.5 GB file rejected");
        require(!new GameBananaInstaller.FileOption("1", "pack.rar", 1_500_000_001L, "")
                .installable(), "oversized file accepted");
        System.out.println("GameBanana mod link checks passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
