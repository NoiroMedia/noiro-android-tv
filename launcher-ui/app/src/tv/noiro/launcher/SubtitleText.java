package tv.noiro.launcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Strict Unicode first, with declared and language-specific legacy subtitle encodings. */
final class SubtitleText {
    private static String strict(byte[] bytes, String charset) throws Exception {
        return Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    static String decode(byte[] input, String contentType, String language) {
        if (input.length > 2 * 1024 * 1024) throw new IllegalArgumentException("subtitle_too_large");
        byte[] bytes = input;
        try {
            if (bytes.length > 2 && (bytes[0] & 255) == 31 && (bytes[1] & 255) == 139) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = gzip.read(buffer)) != -1) { if (out.size() + count > 2 * 1024 * 1024) throw new IllegalArgumentException("subtitle_too_large"); out.write(buffer, 0, count); }
                }
                bytes = out.toByteArray();
            }
            String value = null;
            if (bytes.length >= 2 && ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 254
                    || (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255)) value = strict(bytes, "UTF-16");
            Matcher declared = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([a-z0-9._-]+)").matcher(contentType == null ? "" : contentType);
            if (value == null && declared.find()) try { value = strict(bytes, declared.group(1)); } catch (Exception ignored) {}
            if (value == null) try { value = strict(bytes, "UTF-8"); } catch (Exception ignored) {}
            if (value == null) {
                String code = SubtitleSelection.normalize(language);
                String legacy = "windows-1252";
                if (java.util.Arrays.asList("bs","hr","sr","sl","cs","sk","pl","hu","ro").contains(code)) legacy = "windows-1250";
                else if (java.util.Arrays.asList("ru","uk","bg","mk").contains(code)) legacy = "windows-1251";
                else if (code.equals("tr")) legacy = "windows-1254";
                else if (code.equals("el")) legacy = "windows-1253";
                else if (code.equals("he")) legacy = "windows-1255";
                else if (code.equals("ar")) legacy = "windows-1256";
                value = new String(bytes, Charset.forName(legacy));
            }
            if (value.startsWith("\uFEFF")) value = value.substring(1);
            // Repair reversible UTF-8-as-Latin mojibake; never guess absent accents from ASCII words.
            if (value.matches("(?s).*[ÃÂÄÅ].*")) for (String legacy : new String[]{"windows-1252", "ISO-8859-1"}) {
                try {
                    ByteBuffer encoded = Charset.forName(legacy).newEncoder().onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
                    byte[] raw = new byte[encoded.remaining()]; encoded.get(raw);
                    String repaired = strict(raw, "UTF-8");
                    if (!repaired.equals(value)) { value = repaired; break; }
                } catch (Exception ignored) {}
            }
            return Normalizer.normalize(entities(value), Normalizer.Form.NFC);
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalArgumentException("subtitle_encoding_unavailable"); }
    }
    static String entities(String text) {
        String[] names = {"ccaron","cacute","zcaron","scaron","dstrok","Ccaron","Cacute","Zcaron","Scaron","Dstrok"};
        String[] chars = {"č","ć","ž","š","đ","Č","Ć","Ž","Š","Đ"};
        for (int i = 0; i < names.length; i++) text = text.replace("&" + names[i] + ";", chars[i]);
        Matcher m = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(text);
        StringBuffer result = new StringBuffer();
        while (m.find()) {
            String replacement = m.group();
            try { String number = m.group(1); int cp = Integer.parseInt(number.startsWith("x") ? number.substring(1) : number, number.startsWith("x") ? 16 : 10);
                if (Character.isValidCodePoint(cp) && cp > 0 && !(cp >= 0xD800 && cp <= 0xDFFF)) replacement = new String(Character.toChars(cp));
            } catch (Exception ignored) {}
            m.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(result); return result.toString();
    }
}
