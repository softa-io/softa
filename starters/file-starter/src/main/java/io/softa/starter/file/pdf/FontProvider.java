package io.softa.starter.file.pdf;

import java.io.File;
import java.util.EnumSet;
import java.util.List;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FSFontUseCase;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;

/**
 * Discovers and registers Noto fonts installed by {@code deploy/install-font.sh}.
 * <p>
 * Base families (Sans / Serif / Mono) are registered under common web-safe aliases
 * (Arial, Times New Roman, Courier New, etc.). Script-specific fonts (SC, TC, JP,
 * KR, Arabic, Thai) are registered as {@link FSFontUseCase#FALLBACK_PRE} so the
 * renderer selects the correct font per glyph automatically.
 */
@Slf4j
final class FontProvider {

    private static final EnumSet<FSFontUseCase> FALLBACK_PRE =
            EnumSet.of(FSFontUseCase.FALLBACK_PRE);

    private static final List<String> SANS_ALIASES = List.of(
            "Arial", "Helvetica", "Verdana", "sans-serif", "Noto Sans");
    private static final List<String> SERIF_ALIASES = List.of(
            "Times New Roman", "Times", "Georgia", "serif", "Noto Serif");
    private static final List<String> MONO_ALIASES = List.of(
            "Courier New", "Courier", "monospace", "Noto Sans Mono");

    private static final String[] FALLBACK_FILES = {
            "NotoSansSC-Regular.ttf",
            "NotoSerifSC-Regular.ttf",
            "NotoSansTC-Regular.ttf",
            "NotoSansJP-Regular.ttf",
            "NotoSansKR-Regular.ttf",
            "NotoSansArabic-Regular.ttf",
            "NotoSansThai-Regular.ttf",
    };

    private static final File FONT_DIR = resolveFontDirectory();

    static {
        if (FONT_DIR == null) {
            log.warn("No Noto font directory found — run 'sh deploy/install-font.sh'.");
        } else {
            log.info("Noto font directory: {}", FONT_DIR);
        }
    }

    private FontProvider() {}

    // ── Public API ──

    /** Registers all discovered Noto fonts onto the given builder. */
    static void registerFonts(PdfRendererBuilder builder) {
        registerFamily(builder, "NotoSans",     SANS_ALIASES);
        registerFamily(builder, "NotoSerif",     SERIF_ALIASES);
        registerFamily(builder, "NotoSansMono",  MONO_ALIASES);

        for (String fileName : FALLBACK_FILES) {
            File file = font(fileName);
            if (file != null) {
                String family = fileName.substring(0, fileName.indexOf('-'));
                builder.useFont(file, family, 400, FontStyle.NORMAL, true, FALLBACK_PRE);
            }
        }
    }

    // ── Family registration ──

    private static void registerFamily(PdfRendererBuilder builder,
                                       String baseName, List<String> aliases) {
        File regular = font(baseName + "-Regular.ttf");
        if (regular == null) {
            return;
        }
        File bold       = fontOr(baseName + "-Bold.ttf", regular);
        File italic     = font(baseName + "-Italic.ttf");
        File boldItalic = italic != null ? fontOr(baseName + "-BoldItalic.ttf", italic) : null;

        for (String alias : aliases) {
            builder.useFont(regular, alias, 400, FontStyle.NORMAL, true);
            builder.useFont(bold,    alias, 700, FontStyle.NORMAL, true);
            if (italic != null) {
                builder.useFont(italic,     alias, 400, FontStyle.ITALIC, true);
                builder.useFont(boldItalic, alias, 700, FontStyle.ITALIC, true);
            }
        }
    }

    // ── Font file resolution ──

    private static File font(String fileName) {
        if (FONT_DIR == null) {
            return null;
        }
        File file = new File(FONT_DIR, fileName);
        return file.isFile() ? file : null;
    }

    private static File fontOr(String fileName, File fallback) {
        File file = font(fileName);
        return file != null ? file : fallback;
    }

    /**
     * Mirrors {@code deploy/install-font.sh} directory selection:
     * macOS → {@code ~/Library/Fonts}, Linux root → {@code /usr/share/fonts/noto},
     * Linux non-root → {@code ~/.local/share/fonts}.
     */
    private static File resolveFontDirectory() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return dirIfExists(home + "/Library/Fonts");
        }
        File systemDir = dirIfExists("/usr/share/fonts/noto");
        if (systemDir != null) {
            return systemDir;
        }
        return dirIfExists(home + "/.local/share/fonts");
    }

    private static File dirIfExists(String path) {
        File dir = new File(path);
        return dir.isDirectory() ? dir : null;
    }
}
