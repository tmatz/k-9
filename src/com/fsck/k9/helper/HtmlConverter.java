package com.fsck.k9.helper;

import android.text.*;
import android.text.Html.TagHandler;
import android.util.Log;
import com.fsck.k9.K9;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.internet.MimeUtility;

import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Contains common routines to convert html to text and vice versa.
 */
public class HtmlConverter {
    /**
     * When generating previews, Spannable objects that can't be converted into a String are
     * represented as 0xfffc. When displayed, these show up as undisplayed squares. These constants
     * define the object character and the replacement character.
     */
    private static final char PREVIEW_OBJECT_CHARACTER = (char)0xfffc;
    private static final char PREVIEW_OBJECT_REPLACEMENT = (char)0x20;  // space

    /**
     * toHtml() converts non-breaking spaces into the UTF-8 non-breaking space, which doesn't get
     * rendered properly in some clients. Replace it with a simple space.
     */
    private static final char NBSP_CHARACTER = (char)0x00a0;    // utf-8 non-breaking space
    private static final char NBSP_REPLACEMENT = (char)0x20;    // space

    // Number of extra bytes to allocate in a string buffer for htmlification.
    private static final int TEXT_TO_HTML_EXTRA_BUFFER_LENGTH = 512;

    /**
     * Convert an HTML string to a plain text string.
     * @param html HTML string to convert.
     * @return Plain text result.
     */
    public static String htmlToText(final String html) {
        return Html.fromHtml(html, null, new HtmlToTextTagHandler()).toString()
               .replace(PREVIEW_OBJECT_CHARACTER, PREVIEW_OBJECT_REPLACEMENT)
               .replace(NBSP_CHARACTER, NBSP_REPLACEMENT);
    }

    /**
     * Custom tag handler to use when converting HTML messages to text. It currently handles text
     * representations of HTML tags that Android's built-in parser doesn't understand and hides code
     * contained in STYLE and SCRIPT blocks.
     */
    private static class HtmlToTextTagHandler implements Html.TagHandler {
        // List of tags whose content should be ignored.
        private static final Set<String> TAGS_WITH_IGNORED_CONTENT;
        static {
            Set<String> set = new HashSet<String>();
            set.add("style");
            set.add("script");
            set.add("title");
            set.add("!");   // comments
            TAGS_WITH_IGNORED_CONTENT = Collections.unmodifiableSet(set);
        }

        @Override
        public void handleTag(boolean opening, String tag, Editable output, XMLReader xmlReader) {
            tag = tag.toLowerCase(Locale.US);
            if (tag.equals("hr") && opening) {
                // In the case of an <hr>, replace it with a bunch of underscores. This is roughly
                // the behaviour of Outlook in Rich Text mode.
                output.append("_____________________________________________\r\n");
            } else if (TAGS_WITH_IGNORED_CONTENT.contains(tag)) {
                handleIgnoredTag(opening, output);
            }
        }

        private static final String IGNORED_ANNOTATION_KEY = "K9_ANNOTATION";
        private static final String IGNORED_ANNOTATION_VALUE = "hiddenSpan";

        /**
         * When we come upon an ignored tag, we mark it with an Annotation object with a specific key
         * and value as above. We don't really need to be checking these values since Html.fromHtml()
         * doesn't use Annotation spans, but we should do it now to be safe in case they do start using
         * it in the future.
         * @param opening If this is an opening tag or not.
         * @param output Spannable string that we're working with.
         */
        private void handleIgnoredTag(boolean opening, Editable output) {
            int len = output.length();
            if (opening) {
                output.setSpan(new Annotation(IGNORED_ANNOTATION_KEY, IGNORED_ANNOTATION_VALUE), len,
                               len, Spannable.SPAN_MARK_MARK);
            } else {
                Object start = getOpeningAnnotation(output);
                if (start != null) {
                    int where = output.getSpanStart(start);
                    // Remove the temporary Annotation span.
                    output.removeSpan(start);
                    // Delete everything between the start of the Annotation and the end of the string
                    // (what we've generated so far).
                    output.delete(where, len);
                }
            }
        }

        /**
         * Fetch the matching opening Annotation object and verify that it's the one added by K9.
         * @param output Spannable string we're working with.
         * @return Starting Annotation object.
         */
        private Object getOpeningAnnotation(Editable output) {
            Object[] objs = output.getSpans(0, output.length(), Annotation.class);
            for (int i = objs.length - 1; i >= 0; i--) {
                Annotation span = (Annotation) objs[i];
                if (output.getSpanFlags(objs[i]) == Spannable.SPAN_MARK_MARK
                        && span.getKey().equals(IGNORED_ANNOTATION_KEY)
                        && span.getValue().equals(IGNORED_ANNOTATION_VALUE)) {
                    return objs[i];
                }
            }
            return null;
        }
    }

    private static final int MAX_SMART_HTMLIFY_MESSAGE_LENGTH = 1024 * 256 ;

    /**
     * Naively convert a text string into an HTML document.
     *
     * <p>
     * This method avoids using regular expressions on the entire message body to save memory.
     * </p>
     * <p>
     * No HTML headers or footers are added to the result.  Headers and footers
     * are added at display time in
     * {@link com.fsck.k9.view#MessageWebView.setText(String) MessageWebView.setText()}
     * </p>
     *
     * @param text
     *         Plain text string.
     * @return HTML string.
     */
    private static String simpleTextToHtml(String text) {
        // Encode HTML entities to make sure we don't display something evil.
        text = TextUtils.htmlEncode(text);

        StringReader reader = new StringReader(text);
        StringBuilder buff = new StringBuilder(text.length() + TEXT_TO_HTML_EXTRA_BUFFER_LENGTH);

        buff.append(htmlifyMessageHeader());

        int c;
        try {
            while ((c = reader.read()) != -1) {
                switch (c) {
                case '\n':
                    // pine treats <br> as two newlines, but <br/> as one newline.  Use <br/> so our messages aren't
                    // doublespaced.
                    buff.append("<br />");
                    break;
                case '\r':
                    break;
                default:
                    buff.append((char)c);
                }//switch
            }
        } catch (IOException e) {
            //Should never happen
            Log.e(K9.LOG_TAG, "Could not read string to convert text to HTML:", e);
        }

        buff.append(htmlifyMessageFooter());

        return buff.toString();
    }

    private static final String HTML_BLOCKQUOTE_COLOR_TOKEN = "$$COLOR$$";
    private static final String HTML_BLOCKQUOTE_START = "<blockquote class=\"gmail_quote\" " +
            "style=\"margin: 0pt 0pt 1ex 0.8ex; border-left: 1px solid $$COLOR$$; padding-left: 1ex;\">";
    private static final String HTML_BLOCKQUOTE_END = "</blockquote>";
    private static final String HTML_NEWLINE = "<br />";

    /**
     * Convert a text string into an HTML document.
     *
     * <p>
     * Attempts to do smart replacement for large documents to prevent OOM
     * errors.
     * <p>
     * No HTML headers or footers are added to the result.  Headers and footers
     * are added at display time in
     * {@link com.fsck.k9.view#MessageWebView.setText(String) MessageWebView.setText()}
     * </p>
     * <p>
     * To convert to a fragment, use {@link #textToHtmlFragment(String)} .
     * </p>
     *
     * @param text
     *         Plain text string.
     * @return HTML string.
     */
    public static String textToHtml(String text) {
        // Our HTMLification code is somewhat memory intensive
        // and was causing lots of OOM errors on the market
        // if the message is big and plain text, just do
        // a trivial htmlification
        if (text.length() > MAX_SMART_HTMLIFY_MESSAGE_LENGTH) {
            return simpleTextToHtml(text);
        }
        StringReader reader = new StringReader(text);
        StringBuilder buff = new StringBuilder(text.length() + TEXT_TO_HTML_EXTRA_BUFFER_LENGTH);
        boolean isStartOfLine = true;  // Are we currently at the start of a line?
        int spaces = 0;
        int quoteDepth = 0; // Number of DIVs deep we are.
        int quotesThisLine = 0; // How deep we should be quoting for this line.
        try {
            int c;
            while ((c = reader.read()) != -1) {
                if (isStartOfLine) {
                    switch (c) {
                    case ' ':
                        spaces++;
                        break;
                    case '>':
                        quotesThisLine++;
                        spaces = 0;
                        break;
                    case '\n':
                        appendbq(buff, quotesThisLine, quoteDepth);
                        quoteDepth = quotesThisLine;

                        appendsp(buff, spaces);
                        spaces = 0;

                        appendchar(buff, c);
                        isStartOfLine = true;
                        quotesThisLine = 0;
                        break;
                    default:
                        isStartOfLine = false;

                        appendbq(buff, quotesThisLine, quoteDepth);
                        quoteDepth = quotesThisLine;

                        appendsp(buff, spaces);
                        spaces = 0;

                        appendchar(buff, c);
                        isStartOfLine = false;
                        break;
                    }
                }
                else {
                    appendchar(buff, c);
                    if (c == '\n') {
                        isStartOfLine = true;
                        quotesThisLine = 0;
                    }
                }
            }
        } catch (IOException e) {
            //Should never happen
            Log.e(K9.LOG_TAG, "Could not read string to convert text to HTML:", e);
        }
        // Close off any quotes we may have opened.
        if (quoteDepth > 0) {
            for (int i = quoteDepth; i > 0; i--) {
                buff.append(HTML_BLOCKQUOTE_END);
            }
        }
        text = buff.toString();

        // Make newlines at the end of blockquotes nicer by putting newlines beyond the first one outside of the
        // blockquote.
        text = text.replaceAll(
                   "\\Q" + HTML_NEWLINE + "\\E((\\Q" + HTML_NEWLINE + "\\E)+?)\\Q" + HTML_BLOCKQUOTE_END + "\\E",
                   HTML_BLOCKQUOTE_END + "$1"
               );

        // Replace lines of -,= or _ with horizontal rules
        text = text.replaceAll("\\s*([-=_]{30,}+)\\s*", "<hr />");

        /*
         * Unwrap multi-line paragraphs into single line paragraphs that are
         * wrapped when displayed. But try to avoid unwrapping consecutive lines
         * of text that are not paragraphs, such as lists of system log entries
         * or long URLs that are on their own line.
         */
        text = text.replaceAll("(?m)^([^\r\n]{4,}[\\s\\w,:;+/])(?:\r\n|\n|\r)(?=[a-z]\\S{0,10}[\\s\\n\\r])", "$1 ");

        // Compress four or more newlines down to two newlines
        text = text.replaceAll("(?m)(\r\n|\n|\r){4,}", "\r\n\r\n");

        StringBuffer sb = new StringBuffer(text.length() + TEXT_TO_HTML_EXTRA_BUFFER_LENGTH);

        sb.append(htmlifyMessageHeader());
        linkifyText(text, sb);
        sb.append(htmlifyMessageFooter());

        text = sb.toString();

        // Above we replaced > with <gt>, now make it &gt;
        text = text.replaceAll("<gt>", "&gt;");

        return text;
    }

    private static void appendchar(StringBuilder buff, int c) {
        switch (c) {
        case '&':
            buff.append("&amp;");
            break;
        case '<':
            buff.append("&lt;");
            break;
        case '>':
            // We use a token here which can't occur in htmlified text because &gt; is valid
            // within links (where > is not), and linkifying links will include it if we
            // do it here. We'll make another pass and change this back to &gt; after
            // the linkification is done.
            buff.append("<gt>");
            break;
        case '\r':
            break;
        case '\n':
            // pine treats <br> as two newlines, but <br/> as one newline.  Use <br/> so our messages aren't
            // doublespaced.
            buff.append(HTML_NEWLINE);
            break;
        default:
            buff.append((char)c);
            break;
        }
    }

    private static void appendsp(StringBuilder buff, int spaces) {
        while (spaces > 0) {
            buff.append(' ');
            spaces--;
        }
    }

    private static void appendbq(StringBuilder buff, int quotesThisLine, int quoteDepth) {
        // Add/remove blockquotes by comparing this line's quotes to the previous line's quotes.
        if (quotesThisLine > quoteDepth) {
            for (int i = quoteDepth; i < quotesThisLine; i++) {
                buff.append(HTML_BLOCKQUOTE_START.replace(HTML_BLOCKQUOTE_COLOR_TOKEN, getQuoteColor(i + 1)));
            }
        } else if (quotesThisLine < quoteDepth) {
            for (int i = quoteDepth; i > quotesThisLine; i--) {
                buff.append(HTML_BLOCKQUOTE_END);
            }
        }
    }

    protected static final String QUOTE_COLOR_DEFAULT = "#ccc";
    protected static final String QUOTE_COLOR_LEVEL_1 = "#729fcf";
    protected static final String QUOTE_COLOR_LEVEL_2 = "#ad7fa8";
    protected static final String QUOTE_COLOR_LEVEL_3 = "#8ae234";
    protected static final String QUOTE_COLOR_LEVEL_4 = "#fcaf3e";
    protected static final String QUOTE_COLOR_LEVEL_5 = "#e9b96e";
    private static final String K9MAIL_CSS_CLASS = "k9mail";

    /**
     * Return an HTML hex color string for a given quote level.
     * @param level Quote level
     * @return Hex color string with prepended #.
     */
    protected static String getQuoteColor(final int level) {
        switch(level) {
            case 1:
                return QUOTE_COLOR_LEVEL_1;
            case 2:
                return QUOTE_COLOR_LEVEL_2;
            case 3:
                return QUOTE_COLOR_LEVEL_3;
            case 4:
                return QUOTE_COLOR_LEVEL_4;
            case 5:
                return QUOTE_COLOR_LEVEL_5;
            default:
                return QUOTE_COLOR_DEFAULT;
        }
    }

    /**
     * Searches for link-like text in a string and turn it into a link. Append the result to
     * <tt>outputBuffer</tt>. <tt>text</tt> is not modified.
     * @param text Plain text to be linkified.
     * @param outputBuffer Buffer to append linked text to.
     */
    private static void linkifyText(final String text, final StringBuffer outputBuffer) {
        String prepared = text.replaceAll(Regex.BITCOIN_URI_PATTERN, "<a href=\"$0\">$0</a>");

        Matcher m = Regex.WEB_URL_PATTERN.matcher(prepared);
        while (m.find()) {
            int start = m.start();
            if (start == 0 || (start != 0 && text.charAt(start - 1) != '@')) {
                if (m.group().indexOf(':') > 0) { // With no URI-schema we may get "http:/" links with the second / missing
                    m.appendReplacement(outputBuffer, "<a href=\"$0\">$0</a>");
                } else {
                    m.appendReplacement(outputBuffer, "<a href=\"http://$0\">$0</a>");
                }
            } else {
                m.appendReplacement(outputBuffer, "$0");
            }
        }

        m.appendTail(outputBuffer);
    }

    /*
     * Lightweight method to check whether the message contains emoji or not.
     * Useful to avoid calling the heavyweight convertEmoji2Img method.
     * We don't use String.codePointAt here for performance reasons.
     */
    private static boolean hasEmoji(String html) {
        for (int i = 0; i < html.length(); ++i) {
            char c = html.charAt(i);
            if (c >= 0xDBB8 && c < 0xDBBC)
                return true;
        }
        return false;
    }

    public static String convertEmoji2Img(String html, Address[] fromAddrs) {
        if (!hasEmoji(html)) {
            return html;

        }

        int carrier = 0;
        if (fromAddrs != null && fromAddrs.length > 0) {
            String from = MimeUtility.getJisVariantFromAddress(fromAddrs[0].getAddress());
            if ("docomo".equals(from)) {
                carrier = 0;
            }
            else if ("softbank".equals(from)) {
                carrier = 1;
            }
            else if ("kddi".equals(from)) {
                carrier = 2;
            }
            else {
                carrier = 3;
            }
        }

        StringBuilder buff = new StringBuilder(html.length() + 512);
        for (int i = 0; i < html.length(); i = html.offsetByCodePoints(i, 1)) {
            int codePoint = html.codePointAt(i);
            String emoji = null;
            switch (carrier) {
            case 0:
                emoji = getEmojiForCodePointDocomo(codePoint);
                break;
            case 1:
                emoji = getEmojiForCodePointSoftBank(codePoint);
                break;
            case 2:
                emoji = getEmojiForCodePointKDDI(codePoint);
                break;
            default:
                emoji = getEmojiForCodePointDocomo(codePoint);
                break;
            }
            if (emoji != null)
                buff.append("<img src=\"file:///android_asset/mobylet-emoticons/").append(emoji).append(".gif\" alt=\"").append(emoji).append("\" />");
            else
                buff.appendCodePoint(codePoint);

        }
        return buff.toString();
    }

    private static String getEmojiForCodePointDocomo(int codePoint) {

        switch (codePoint) {

        case 0xFE000: return "E63E";    // Docomo
        case 0xFE001: return "E63F";    // Docomo
        case 0xFE002: return "E640";    // Docomo
        case 0xFE003: return "E641";    // Docomo
        case 0xFE004: return "E642";    // Docomo
        case 0xFE005: return "E643";    // Docomo
        case 0xFE006: return "E644";    // Docomo
        case 0xFE007: return "E645";    // Docomo
        case 0xFE008: return "E6B3";    // Docomo
        case 0xFE009: return "E63E";    // Docomo
        case 0xFE00A: return "E63E";    // Docomo
        case 0xFE00C: return "E63E";    // Docomo
        case 0xFE00F: return "E63EE63F";    // Docomo
        case 0xFE010: return "E6B3";    // Docomo
        case 0xFE011: return "E69C";    // Docomo
        case 0xFE012: return "E69D";    // Docomo
        case 0xFE013: return "E69E";    // Docomo
        case 0xFE014: return "E69F";    // Docomo
        case 0xFE015: return "E6A0";    // Docomo
        case 0xFE016: return "E69E";    // Docomo
        case 0xFE018: return "E6B7";    // Docomo
        case 0xFE019: return "E6B8";    // Docomo
        case 0xFE01A: return "E6B9";    // Docomo
        case 0xFE01B: return "E71C";    // Docomo
        case 0xFE01C: return "E71C";    // Docomo
        case 0xFE01D: return "E71F";    // Docomo
        case 0xFE01E: return "E6BA";    // Docomo
        case 0xFE01F: return "E6BA";    // Docomo
        case 0xFE020: return "E6BA";    // Docomo
        case 0xFE021: return "E6BA";    // Docomo
        case 0xFE022: return "E6BA";    // Docomo
        case 0xFE023: return "E6BA";    // Docomo
        case 0xFE024: return "E6BA";    // Docomo
        case 0xFE025: return "E6BA";    // Docomo
        case 0xFE026: return "E6BA";    // Docomo
        case 0xFE027: return "E6BA";    // Docomo
        case 0xFE028: return "E6BA";    // Docomo
        case 0xFE029: return "E6BA";    // Docomo
        case 0xFE02A: return "E6BA";    // Docomo
        case 0xFE02B: return "E646";    // Docomo
        case 0xFE02C: return "E647";    // Docomo
        case 0xFE02D: return "E648";    // Docomo
        case 0xFE02E: return "E649";    // Docomo
        case 0xFE02F: return "E64A";    // Docomo
        case 0xFE030: return "E64B";    // Docomo
        case 0xFE031: return "E64C";    // Docomo
        case 0xFE032: return "E64D";    // Docomo
        case 0xFE033: return "E64E";    // Docomo
        case 0xFE034: return "E64F";    // Docomo
        case 0xFE035: return "E650";    // Docomo
        case 0xFE036: return "E651";    // Docomo
        case 0xFE038: return "E73F";    // Docomo
        case 0xFE03B: return "E6B3";    // Docomo
        case 0xFE03C: return "E741";    // Docomo
        case 0xFE03D: return "E743";    // Docomo
        case 0xFE03E: return "E746";    // Docomo
        case 0xFE03F: return "E747";    // Docomo
        case 0xFE040: return "E748";    // Docomo
        case 0xFE042: return "E747";    // Docomo
        case 0xFE04E: return "E741";    // Docomo
        case 0xFE04F: return "E742";    // Docomo
        case 0xFE050: return "E744";    // Docomo
        case 0xFE051: return "E745";    // Docomo
        case 0xFE05B: return "E745";    // Docomo
        case 0xFE190: return "E691";    // Docomo
        case 0xFE191: return "E692";    // Docomo
        case 0xFE193: return "E6F9";    // Docomo
        case 0xFE194: return "E728";    // Docomo
        case 0xFE195: return "E710";    // Docomo
        case 0xFE198: return "E675";    // Docomo
        case 0xFE19A: return "E6B1";    // Docomo
        case 0xFE19B: return "E6F0";    // Docomo
        case 0xFE19C: return "E6F0";    // Docomo
        case 0xFE19D: return "E6F0";    // Docomo
        case 0xFE19E: return "E6F0";    // Docomo
        case 0xFE1B7: return "E6A1";    // Docomo
        case 0xFE1B8: return "E6A2";    // Docomo
        case 0xFE1B9: return "E74E";    // Docomo
        case 0xFE1BA: return "E74F";    // Docomo
        case 0xFE1BB: return "E74F";    // Docomo
        case 0xFE1BC: return "E750";    // Docomo
        case 0xFE1BD: return "E751";    // Docomo
        case 0xFE1BE: return "E754";    // Docomo
        case 0xFE1BF: return "E755";    // Docomo
        case 0xFE1C8: return "E74F";    // Docomo
        case 0xFE1C9: return "E751";    // Docomo
        case 0xFE1D0: return "E6A1";    // Docomo
        case 0xFE1D8: return "E6A1";    // Docomo
        case 0xFE1D9: return "E751";    // Docomo
        case 0xFE1DB: return "E698";    // Docomo
        case 0xFE1DD: return "E74F";    // Docomo
        case 0xFE1E0: return "E755";    // Docomo
        case 0xFE320: return "E6F1";    // Docomo
        case 0xFE321: return "E6F3";    // Docomo
        case 0xFE322: return "E6F4";    // Docomo
        case 0xFE323: return "E6F2";    // Docomo
        case 0xFE324: return "E6F4";    // Docomo
        case 0xFE325: return "E723";    // Docomo
        case 0xFE326: return "E725";    // Docomo
        case 0xFE327: return "E726";    // Docomo
        case 0xFE328: return "E753";    // Docomo
        case 0xFE329: return "E728";    // Docomo
        case 0xFE32A: return "E728";    // Docomo
        case 0xFE32B: return "E752";    // Docomo
        case 0xFE32C: return "E726";    // Docomo
        case 0xFE32D: return "E726";    // Docomo
        case 0xFE32F: return "E72A";    // Docomo
        case 0xFE330: return "E6F0";    // Docomo
        case 0xFE331: return "E722";    // Docomo
        case 0xFE332: return "E72A";    // Docomo
        case 0xFE333: return "E753";    // Docomo
        case 0xFE334: return "E72A";    // Docomo
        case 0xFE335: return "E6F0";    // Docomo
        case 0xFE336: return "E6F0";    // Docomo
        case 0xFE337: return "E6F0";    // Docomo
        case 0xFE338: return "E6F0";    // Docomo
        case 0xFE339: return "E72E";    // Docomo
        case 0xFE33A: return "E72D";    // Docomo
        case 0xFE33B: return "E757";    // Docomo
        case 0xFE33C: return "E72B";    // Docomo
        case 0xFE33D: return "E724";    // Docomo
        case 0xFE33E: return "E721";    // Docomo
        case 0xFE33F: return "E6F3";    // Docomo
        case 0xFE340: return "E720";    // Docomo
        case 0xFE341: return "E757";    // Docomo
        case 0xFE342: return "E701";    // Docomo
        case 0xFE343: return "E72C";    // Docomo
        case 0xFE344: return "E723";    // Docomo
        case 0xFE345: return "E723";    // Docomo
        case 0xFE346: return "E72B";    // Docomo
        case 0xFE347: return "E729";    // Docomo
        case 0xFE348: return "E6F0";    // Docomo
        case 0xFE349: return "E753";    // Docomo
        case 0xFE34A: return "E72A";    // Docomo
        case 0xFE34B: return "E726";    // Docomo
        case 0xFE34C: return "E726";    // Docomo
        case 0xFE34D: return "E72E";    // Docomo
        case 0xFE34E: return "E724";    // Docomo
        case 0xFE34F: return "E753";    // Docomo
        case 0xFE350: return "E6F3";    // Docomo
        case 0xFE351: return "E72F";    // Docomo
        case 0xFE352: return "E70B";    // Docomo
        case 0xFE359: return "E6F3";    // Docomo
        case 0xFE35A: return "E6F1";    // Docomo
        case 0xFE4B0: return "E663";    // Docomo
        case 0xFE4B1: return "E663";    // Docomo
        case 0xFE4B2: return "E664";    // Docomo
        case 0xFE4B3: return "E665";    // Docomo
        case 0xFE4B4: return "E666";    // Docomo
        case 0xFE4B5: return "E667";    // Docomo
        case 0xFE4B6: return "E668";    // Docomo
        case 0xFE4B7: return "E669";    // Docomo
        case 0xFE4B8: return "E669E6EF";    // Docomo
        case 0xFE4B9: return "E66A";    // Docomo
        case 0xFE4BA: return "E73E";    // Docomo
        case 0xFE4C1: return "E661";    // Docomo
        case 0xFE4C2: return "E74B";    // Docomo
        case 0xFE4C3: return "E740";    // Docomo
        case 0xFE4C9: return "E718";    // Docomo
        case 0xFE4CC: return "E699";    // Docomo
        case 0xFE4CD: return "E699";    // Docomo
        case 0xFE4CE: return "E69A";    // Docomo
        case 0xFE4CF: return "E70E";    // Docomo
        case 0xFE4D0: return "E711";    // Docomo
        case 0xFE4D1: return "E71A";    // Docomo
        case 0xFE4D2: return "E71A";    // Docomo
        case 0xFE4D6: return "E674";    // Docomo
        case 0xFE4D7: return "E674";    // Docomo
        case 0xFE4DB: return "E70E";    // Docomo
        case 0xFE4DC: return "E70F";    // Docomo
        case 0xFE4DD: return "E715";    // Docomo
        case 0xFE4E0: return "E715";    // Docomo
        case 0xFE4E2: return "E6D6";    // Docomo
        case 0xFE4E3: return "E715";    // Docomo
        case 0xFE4EF: return "E681";    // Docomo
        case 0xFE4F0: return "E682";    // Docomo
        case 0xFE4F1: return "E6AD";    // Docomo
        case 0xFE4F2: return "E713";    // Docomo
        case 0xFE4F3: return "E714";    // Docomo
        case 0xFE4F9: return "E677";    // Docomo
        case 0xFE4FB: return "E6FB";    // Docomo
        case 0xFE4FD: return "E70A";    // Docomo
        case 0xFE4FF: return "E683";    // Docomo
        case 0xFE500: return "E683";    // Docomo
        case 0xFE501: return "E683";    // Docomo
        case 0xFE502: return "E683";    // Docomo
        case 0xFE503: return "E683";    // Docomo
        case 0xFE505: return "E6F7";    // Docomo
        case 0xFE506: return "E66E";    // Docomo
        case 0xFE507: return "E66E";    // Docomo
        case 0xFE508: return "E66E";    // Docomo
        case 0xFE50F: return "E684";    // Docomo
        case 0xFE510: return "E685";    // Docomo
        case 0xFE511: return "E686";    // Docomo
        case 0xFE512: return "E6A4";    // Docomo
        case 0xFE522: return "E65A";    // Docomo
        case 0xFE523: return "E687";    // Docomo
        case 0xFE524: return "E687";    // Docomo
        case 0xFE525: return "E688";    // Docomo
        case 0xFE526: return "E6CE";    // Docomo
        case 0xFE527: return "E689";    // Docomo
        case 0xFE528: return "E6D0";    // Docomo
        case 0xFE529: return "E6D3";    // Docomo
        case 0xFE52A: return "E6CF";    // Docomo
        case 0xFE52B: return "E6CF";    // Docomo
        case 0xFE52C: return "E665";    // Docomo
        case 0xFE52D: return "E665";    // Docomo
        case 0xFE52E: return "E665";    // Docomo
        case 0xFE535: return "E685";    // Docomo
        case 0xFE536: return "E6AE";    // Docomo
        case 0xFE537: return "E6B2";    // Docomo
        case 0xFE538: return "E716";    // Docomo
        case 0xFE539: return "E719";    // Docomo
        case 0xFE53A: return "E730";    // Docomo
        case 0xFE53B: return "E682";    // Docomo
        case 0xFE53E: return "E675";    // Docomo
        case 0xFE540: return "E689";    // Docomo
        case 0xFE541: return "E689";    // Docomo
        case 0xFE545: return "E683";    // Docomo
        case 0xFE546: return "E683";    // Docomo
        case 0xFE547: return "E683";    // Docomo
        case 0xFE548: return "E689";    // Docomo
        case 0xFE54D: return "E683";    // Docomo
        case 0xFE54F: return "E683";    // Docomo
        case 0xFE552: return "E689";    // Docomo
        case 0xFE553: return "E698";    // Docomo
        case 0xFE7D0: return "E652";    // Docomo
        case 0xFE7D1: return "E653";    // Docomo
        case 0xFE7D2: return "E654";    // Docomo
        case 0xFE7D3: return "E655";    // Docomo
        case 0xFE7D4: return "E656";    // Docomo
        case 0xFE7D5: return "E657";    // Docomo
        case 0xFE7D6: return "E658";    // Docomo
        case 0xFE7D7: return "E659";    // Docomo
        case 0xFE7D8: return "E712";    // Docomo
        case 0xFE7D9: return "E733";    // Docomo
        case 0xFE7DA: return "E712";    // Docomo
        case 0xFE7DC: return "E754";    // Docomo
        case 0xFE7DF: return "E65B";    // Docomo
        case 0xFE7E0: return "E65C";    // Docomo
        case 0xFE7E1: return "E65C";    // Docomo
        case 0xFE7E2: return "E65D";    // Docomo
        case 0xFE7E3: return "E65D";    // Docomo
        case 0xFE7E4: return "E65E";    // Docomo
        case 0xFE7E5: return "E65F";    // Docomo
        case 0xFE7E6: return "E660";    // Docomo
        case 0xFE7E8: return "E661";    // Docomo
        case 0xFE7E9: return "E662";    // Docomo
        case 0xFE7EA: return "E6A3";    // Docomo
        case 0xFE7EB: return "E71D";    // Docomo
        case 0xFE7EE: return "E6A3";    // Docomo
        case 0xFE7EF: return "E65E";    // Docomo
        case 0xFE7F0: return "E733";    // Docomo
        case 0xFE7F5: return "E66B";    // Docomo
        case 0xFE7F6: return "E66C";    // Docomo
        case 0xFE7F7: return "E66D";    // Docomo
        case 0xFE7FA: return "E6F7";    // Docomo
        case 0xFE7FC: return "E679";    // Docomo
        case 0xFE7FF: return "E751";    // Docomo
        case 0xFE800: return "E676";    // Docomo
        case 0xFE801: return "E677";    // Docomo
        case 0xFE802: return "E677";    // Docomo
        case 0xFE803: return "E67A";    // Docomo
        case 0xFE804: return "E67B";    // Docomo
        case 0xFE805: return "E67C";    // Docomo
        case 0xFE806: return "E67D";    // Docomo
        case 0xFE807: return "E67E";    // Docomo
        case 0xFE808: return "E6AC";    // Docomo
        case 0xFE80A: return "E68B";    // Docomo
        case 0xFE813: return "E6F6";    // Docomo
        case 0xFE814: return "E6FF";    // Docomo
        case 0xFE81A: return "E6FF";    // Docomo
        case 0xFE81C: return "E68A";    // Docomo
        case 0xFE81D: return "E68C";    // Docomo
        case 0xFE81E: return "E68C";    // Docomo
        case 0xFE823: return "E6F9";    // Docomo
        case 0xFE824: return "E717";    // Docomo
        case 0xFE825: return "E71B";    // Docomo
        case 0xFE826: return "E71B";    // Docomo
        case 0xFE827: return "E6F9";    // Docomo
        case 0xFE829: return "E6ED";    // Docomo
        case 0xFE82B: return "E6DF";    // Docomo
        case 0xFE82C: return "E6E0";    // Docomo
        case 0xFE82D: return "E6E1";    // Docomo
        case 0xFE82E: return "E6E2";    // Docomo
        case 0xFE82F: return "E6E3";    // Docomo
        case 0xFE830: return "E6E4";    // Docomo
        case 0xFE831: return "E6E5";    // Docomo
        case 0xFE832: return "E6E6";    // Docomo
        case 0xFE833: return "E6E7";    // Docomo
        case 0xFE834: return "E6E8";    // Docomo
        case 0xFE835: return "E6E9";    // Docomo
        case 0xFE836: return "E6EA";    // Docomo
        case 0xFE837: return "E6EB";    // Docomo
        case 0xFE960: return "E673";    // Docomo
        case 0xFE961: return "E749";    // Docomo
        case 0xFE962: return "E74A";    // Docomo
        case 0xFE963: return "E74C";    // Docomo
        case 0xFE964: return "E74D";    // Docomo
        case 0xFE96A: return "E74C";    // Docomo
        case 0xFE973: return "E643";    // Docomo
        case 0xFE980: return "E66F";    // Docomo
        case 0xFE981: return "E670";    // Docomo
        case 0xFE982: return "E671";    // Docomo
        case 0xFE983: return "E672";    // Docomo
        case 0xFE984: return "E71E";    // Docomo
        case 0xFE985: return "E74B";    // Docomo
        case 0xFE986: return "E756";    // Docomo
        case 0xFE987: return "E672";    // Docomo
        case 0xFE988: return "E671";    // Docomo
        case 0xFEAF0: return "E678";    // Docomo
        case 0xFEAF1: return "E696";    // Docomo
        case 0xFEAF2: return "E697";    // Docomo
        case 0xFEAF3: return "E6A5";    // Docomo
        case 0xFEAF4: return "E6F5";    // Docomo
        case 0xFEAF5: return "E700";    // Docomo
        case 0xFEAF6: return "E73C";    // Docomo
        case 0xFEAF7: return "E73D";    // Docomo
        case 0xFEB04: return "E702";    // Docomo
        case 0xFEB05: return "E703";    // Docomo
        case 0xFEB06: return "E704";    // Docomo
        case 0xFEB07: return "E709";    // Docomo
        case 0xFEB08: return "E70A";    // Docomo
        case 0xFEB0B: return "E702";    // Docomo
        case 0xFEB0C: return "E6EC";    // Docomo
        case 0xFEB0D: return "E6ED";    // Docomo
        case 0xFEB0E: return "E6EE";    // Docomo
        case 0xFEB0F: return "E6EF";    // Docomo
        case 0xFEB10: return "E6EC";    // Docomo
        case 0xFEB11: return "E6ED";    // Docomo
        case 0xFEB12: return "E6EC";    // Docomo
        case 0xFEB13: return "E6EC";    // Docomo
        case 0xFEB14: return "E6EC";    // Docomo
        case 0xFEB15: return "E6EC";    // Docomo
        case 0xFEB16: return "E6EC";    // Docomo
        case 0xFEB17: return "E6EC";    // Docomo
        case 0xFEB18: return "E6ED";    // Docomo
        case 0xFEB19: return "E6F8";    // Docomo
        case 0xFEB1A: return "E68D";    // Docomo
        case 0xFEB1B: return "E68E";    // Docomo
        case 0xFEB1C: return "E68F";    // Docomo
        case 0xFEB1D: return "E690";    // Docomo
        case 0xFEB1E: return "E67F";    // Docomo
        case 0xFEB1F: return "E680";    // Docomo
        case 0xFEB20: return "E69B";    // Docomo
        case 0xFEB21: return "E6D7";    // Docomo
        case 0xFEB22: return "E6DE";    // Docomo
        case 0xFEB23: return "E737";    // Docomo
        case 0xFEB26: return "E72F";    // Docomo
        case 0xFEB27: return "E70B";    // Docomo
        case 0xFEB28: return "E72F";    // Docomo
        case 0xFEB29: return "E731";    // Docomo
        case 0xFEB2A: return "E732";    // Docomo
        case 0xFEB2B: return "E734";    // Docomo
        case 0xFEB2C: return "E735";    // Docomo
        case 0xFEB2D: return "E736";    // Docomo
        case 0xFEB2E: return "E738";    // Docomo
        case 0xFEB2F: return "E739";    // Docomo
        case 0xFEB30: return "E73A";    // Docomo
        case 0xFEB31: return "E73B";    // Docomo
        case 0xFEB36: return "E6DD";    // Docomo
        case 0xFEB44: return "E6A0";    // Docomo
        case 0xFEB48: return "E738";    // Docomo
        case 0xFEB55: return "E6F8";    // Docomo
        case 0xFEB56: return "E6FB";    // Docomo
        case 0xFEB57: return "E6FC";    // Docomo
        case 0xFEB58: return "E6FE";    // Docomo
        case 0xFEB59: return "E701";    // Docomo
        case 0xFEB5A: return "E705";    // Docomo
        case 0xFEB5B: return "E706";    // Docomo
        case 0xFEB5C: return "E707";    // Docomo
        case 0xFEB5D: return "E708";    // Docomo
        case 0xFEB60: return "E6FA";    // Docomo
        case 0xFEB61: return "E6F8";    // Docomo
        case 0xFEB62: return "E6F8";    // Docomo
        case 0xFEB63: return "E69C";    // Docomo
        case 0xFEB64: return "E69C";    // Docomo
        case 0xFEB65: return "E69C";    // Docomo
        case 0xFEB66: return "E69C";    // Docomo
        case 0xFEB67: return "E69C";    // Docomo
        case 0xFEB77: return "E6FA";    // Docomo
        case 0xFEB81: return "E6D8";    // Docomo
        case 0xFEB82: return "E6D9";    // Docomo
        case 0xFEB83: return "E6DA";    // Docomo
        case 0xFEB84: return "E6DB";    // Docomo
        case 0xFEB85: return "E6DC";    // Docomo
        case 0xFEB86: return "E6D9";    // Docomo
        case 0xFEB87: return "E6D9";    // Docomo
        case 0xFEB8A: return "E6D9";    // Docomo
        case 0xFEB8D: return "E6DC";    // Docomo
        case 0xFEB90: return "E6D9";    // Docomo
        case 0xFEB91: return "E735";    // Docomo
        case 0xFEB92: return "E6D3";    // Docomo
        case 0xFEB93: return "E693";    // Docomo
        case 0xFEB94: return "E694";    // Docomo
        case 0xFEB95: return "E695";    // Docomo
        case 0xFEB96: return "E6FD";    // Docomo
        case 0xFEB97: return "E727";    // Docomo
        case 0xFEB9D: return "E695";    // Docomo
        case 0xFEB9F: return "E70B";    // Docomo
        case 0xFEBA0: return "E700";    // Docomo
        case 0xFEBA1: return "E695";    // Docomo
        case 0xFEE10: return "E6D1";    // Docomo
        case 0xFEE11: return "E6D2";    // Docomo
        case 0xFEE12: return "E6D4";    // Docomo
        case 0xFEE13: return "E6D5";    // Docomo
        case 0xFEE14: return "E70C";    // Docomo
        case 0xFEE15: return "E70D";    // Docomo
        case 0xFEE16: return "E6A6";    // Docomo
        case 0xFEE17: return "E6A7";    // Docomo
        case 0xFEE18: return "E6A8";    // Docomo
        case 0xFEE19: return "E6A9";    // Docomo
        case 0xFEE1A: return "E6AA";    // Docomo
        case 0xFEE1B: return "E6AB";    // Docomo
        case 0xFEE1C: return "E6AF";    // Docomo
        case 0xFEE1D: return "E6B0";    // Docomo
        case 0xFEE1E: return "E6B4";    // Docomo
        case 0xFEE1F: return "E6B5";    // Docomo
        case 0xFEE20: return "E6B6";    // Docomo
        case 0xFEE21: return "E6BB";    // Docomo
        case 0xFEE22: return "E6BC";    // Docomo
        case 0xFEE23: return "E6BD";    // Docomo
        case 0xFEE24: return "E6BE";    // Docomo
        case 0xFEE25: return "E6BF";    // Docomo
        case 0xFEE26: return "E6C0";    // Docomo
        case 0xFEE27: return "E6C1";    // Docomo
        case 0xFEE28: return "E6C2";    // Docomo
        case 0xFEE29: return "E6C3";    // Docomo
        case 0xFEE2A: return "E6C4";    // Docomo
        case 0xFEE2B: return "E6C5";    // Docomo
        case 0xFEE2C: return "E6C6";    // Docomo
        case 0xFEE2D: return "E6C7";    // Docomo
        case 0xFEE2E: return "E6C8";    // Docomo
        case 0xFEE2F: return "E6C9";    // Docomo
        case 0xFEE30: return "E6CA";    // Docomo
        case 0xFEE31: return "E6CB";    // Docomo
        case 0xFEE32: return "E6CC";    // Docomo
        case 0xFEE33: return "E6CD";    // Docomo

        default: return null;
        }
    }

    private static String getEmojiForCodePointKDDI(int codePoint) {

        switch (codePoint) {

        case 0xFE000: return "E92C";    // KDDI
        case 0xFE001: return "E96B";    // KDDI
        case 0xFE002: return "E95F";    // KDDI
        case 0xFE003: return "E9BF";    // KDDI
        case 0xFE004: return "E910";    // KDDI
        case 0xFE005: return "E9BE";    // KDDI
        case 0xFE006: return "EA31";    // KDDI
        case 0xFE007: return "EAE1";    // KDDI
        case 0xFE008: return "EAEA";    // KDDI
        case 0xFE009: return "EAED";    // KDDI
        case 0xFE00A: return "EAED";    // KDDI
        case 0xFE00B: return "EA73";    // KDDI
        case 0xFE00C: return "EA73";    // KDDI
        case 0xFE00D: return "EAEB";    // KDDI
        case 0xFE00E: return "E93C";    // KDDI
        case 0xFE00F: return "E9A7";    // KDDI
        case 0xFE010: return "E9E3";    // KDDI
        case 0xFE011: return "EA41";    // KDDI
        case 0xFE012: return "EA42";    // KDDI
        case 0xFE013: return "EA43";    // KDDI
        case 0xFE014: return "E90F";    // KDDI
        case 0xFE016: return "E92F";    // KDDI
        case 0xFE017: return "EAE8";    // KDDI
        case 0xFE01B: return "E93A";    // KDDI
        case 0xFE01C: return "E939";    // KDDI
        case 0xFE01D: return "E919";    // KDDI
        case 0xFE01E: return "E92E";    // KDDI
        case 0xFE01F: return "E92E";    // KDDI
        case 0xFE020: return "E92E";    // KDDI
        case 0xFE021: return "E92E";    // KDDI
        case 0xFE022: return "E92E";    // KDDI
        case 0xFE023: return "E92E";    // KDDI
        case 0xFE024: return "E92E";    // KDDI
        case 0xFE025: return "E92E";    // KDDI
        case 0xFE026: return "E92E";    // KDDI
        case 0xFE027: return "E92E";    // KDDI
        case 0xFE028: return "E92E";    // KDDI
        case 0xFE029: return "E92E";    // KDDI
        case 0xFE02A: return "E92E";    // KDDI
        case 0xFE02B: return "E9C0";    // KDDI
        case 0xFE02C: return "E9C1";    // KDDI
        case 0xFE02D: return "E9C2";    // KDDI
        case 0xFE02E: return "E9C3";    // KDDI
        case 0xFE02F: return "E9C4";    // KDDI
        case 0xFE030: return "E9C5";    // KDDI
        case 0xFE031: return "E9C6";    // KDDI
        case 0xFE032: return "E9C7";    // KDDI
        case 0xFE033: return "E9C8";    // KDDI
        case 0xFE034: return "E9C9";    // KDDI
        case 0xFE035: return "E9CA";    // KDDI
        case 0xFE036: return "E9CB";    // KDDI
        case 0xFE037: return "E9CC";    // KDDI
        case 0xFE038: return "EB75";    // KDDI
        case 0xFE039: return "EA4C";    // KDDI
        case 0xFE03A: return "EB4C";    // KDDI
        case 0xFE03B: return "EB58";    // KDDI
        case 0xFE03C: return "E935";    // KDDI
        case 0xFE03D: return "E971";    // KDDI
        case 0xFE03E: return "EB76";    // KDDI
        case 0xFE03F: return "E985";    // KDDI
        case 0xFE040: return "E9EB";    // KDDI
        case 0xFE041: return "EA53";    // KDDI
        case 0xFE042: return "EA66";    // KDDI
        case 0xFE043: return "EA66";    // KDDI
        case 0xFE044: return "E9B3";    // KDDI
        case 0xFE045: return "EA8D";    // KDDI
        case 0xFE046: return "EA00";    // KDDI
        case 0xFE047: return "E9FF";    // KDDI
        case 0xFE048: return "EA8F";    // KDDI
        case 0xFE04A: return "EB2F";    // KDDI
        case 0xFE04B: return "EB30";    // KDDI
        case 0xFE04C: return "EB31";    // KDDI
        case 0xFE04D: return "EB42";    // KDDI
        case 0xFE04E: return "EB7B";    // KDDI
        case 0xFE04F: return "E9F1";    // KDDI
        case 0xFE050: return "EB2E";    // KDDI
        case 0xFE051: return "EAB2";    // KDDI
        case 0xFE052: return "EAB3";    // KDDI
        case 0xFE053: return "E9F3";    // KDDI
        case 0xFE054: return "E9EE";    // KDDI
        case 0xFE055: return "EAB4";    // KDDI
        case 0xFE056: return "EAB5";    // KDDI
        case 0xFE057: return "EB2B";    // KDDI
        case 0xFE058: return "EB2C";    // KDDI
        case 0xFE059: return "EB2D";    // KDDI
        case 0xFE05A: return "EB32";    // KDDI
        case 0xFE05B: return "EB53";    // KDDI
        case 0xFE190: return "EA3D";    // KDDI
        case 0xFE191: return "EA3E";    // KDDI
        case 0xFE192: return "EAC9";    // KDDI
        case 0xFE193: return "EACA";    // KDDI
        case 0xFE194: return "EB40";    // KDDI
        case 0xFE195: return "EA27";    // KDDI
        case 0xFE196: return "EA99";    // KDDI
        case 0xFE197: return "EA29";    // KDDI
        case 0xFE198: return "EA9A";    // KDDI
        case 0xFE199: return "EA9B";    // KDDI
        case 0xFE19B: return "E950";    // KDDI
        case 0xFE19C: return "E932";    // KDDI
        case 0xFE19D: return "E950";    // KDDI
        case 0xFE19E: return "E932";    // KDDI
        case 0xFE19F: return "E9A3";    // KDDI
        case 0xFE1A1: return "EA76";    // KDDI
        case 0xFE1A2: return "EAD4";    // KDDI
        case 0xFE1A3: return "EAE2";    // KDDI
        case 0xFE1A4: return "EB0C";    // KDDI
        case 0xFE1A5: return "EB0D";    // KDDI
        case 0xFE1A6: return "EB0E";    // KDDI
        case 0xFE1A7: return "EB0F";    // KDDI
        case 0xFE1A8: return "EB10";    // KDDI
        case 0xFE1A9: return "EB11";    // KDDI
        case 0xFE1AA: return "EB12";    // KDDI
        case 0xFE1AB: return "EB13";    // KDDI
        case 0xFE1AC: return "EB3D";    // KDDI
        case 0xFE1AD: return "EB3E";    // KDDI
        case 0xFE1AE: return "E9EC";    // KDDI
        case 0xFE1AF: return "EA58";    // KDDI
        case 0xFE1B0: return "EA2E";    // KDDI
        case 0xFE1B1: return "EA12";    // KDDI
        case 0xFE1B2: return "EA15";    // KDDI
        case 0xFE1B3: return "EA1E";    // KDDI
        case 0xFE1B6: return "EB15";    // KDDI
        case 0xFE1B7: return "E986";    // KDDI
        case 0xFE1B8: return "E9FB";    // KDDI
        case 0xFE1B9: return "EB77";    // KDDI
        case 0xFE1BA: return "E94E";    // KDDI
        case 0xFE1BB: return "EB6F";    // KDDI
        case 0xFE1BC: return "E9FC";    // KDDI
        case 0xFE1BD: return "E9CB";    // KDDI
        case 0xFE1BE: return "E9F8";    // KDDI
        case 0xFE1BF: return "E9FE";    // KDDI
        case 0xFE1C0: return "EA59";    // KDDI
        case 0xFE1C1: return "EA5A";    // KDDI
        case 0xFE1C2: return "EA5B";    // KDDI
        case 0xFE1C3: return "E9F6";    // KDDI
        case 0xFE1C4: return "E9F9";    // KDDI
        case 0xFE1C5: return "EA60";    // KDDI
        case 0xFE1C6: return "EAE5";    // KDDI
        case 0xFE1C7: return "EB14";    // KDDI
        case 0xFE1C8: return "E94E";    // KDDI
        case 0xFE1C9: return "EB16";    // KDDI
        case 0xFE1CB: return "EB17";    // KDDI
        case 0xFE1CC: return "EB18";    // KDDI
        case 0xFE1CD: return "EB19";    // KDDI
        case 0xFE1CE: return "E9F9";    // KDDI
        case 0xFE1CF: return "E9C0";    // KDDI
        case 0xFE1D0: return "E986";    // KDDI
        case 0xFE1D1: return "EB1A";    // KDDI
        case 0xFE1D2: return "E9F7";    // KDDI
        case 0xFE1D3: return "EB1B";    // KDDI
        case 0xFE1D4: return "EB1C";    // KDDI
        case 0xFE1D5: return "EB1D";    // KDDI
        case 0xFE1D6: return "EB1E";    // KDDI
        case 0xFE1D7: return "E9FA";    // KDDI
        case 0xFE1D8: return "E94A";    // KDDI
        case 0xFE1D9: return "E9F2";    // KDDI
        case 0xFE1DA: return "E9FD";    // KDDI
        case 0xFE1DB: return "EA14";    // KDDI
        case 0xFE1DC: return "EA6D";    // KDDI
        case 0xFE1DD: return "EA74";    // KDDI
        case 0xFE1DE: return "EB38";    // KDDI
        case 0xFE1DF: return "EB3F";    // KDDI
        case 0xFE1E0: return "EB41";    // KDDI
        case 0xFE1E1: return "EB50";    // KDDI
        case 0xFE1E2: return "EB51";    // KDDI
        case 0xFE320: return "EA02";    // KDDI
        case 0xFE321: return "EB60";    // KDDI
        case 0xFE322: return "EAC3";    // KDDI
        case 0xFE323: return "EAB9";    // KDDI
        case 0xFE324: return "EA47";    // KDDI
        case 0xFE325: return "EAC4";    // KDDI
        case 0xFE326: return "EAC2";    // KDDI
        case 0xFE327: return "EA5D";    // KDDI
        case 0xFE328: return "EABA";    // KDDI
        case 0xFE329: return "EA08";    // KDDI
        case 0xFE32A: return "EA08";    // KDDI
        case 0xFE32B: return "EAC6";    // KDDI
        case 0xFE32C: return "EAC8";    // KDDI
        case 0xFE32D: return "EAC7";    // KDDI
        case 0xFE32E: return "EAC0";    // KDDI
        case 0xFE32F: return "EAC1";    // KDDI
        case 0xFE330: return "EA01";    // KDDI
        case 0xFE331: return "E471E5B1";    // KDDI
        case 0xFE332: return "EABE";    // KDDI
        case 0xFE333: return "EB79";    // KDDI
        case 0xFE334: return "EB5D";    // KDDI
        case 0xFE335: return "EAC6";    // KDDI
        case 0xFE336: return "E944";    // KDDI
        case 0xFE338: return "EA01";    // KDDI
        case 0xFE339: return "EB62";    // KDDI
        case 0xFE33A: return "EA03";    // KDDI
        case 0xFE33B: return "EABF";    // KDDI
        case 0xFE33C: return "EABB";    // KDDI
        case 0xFE33D: return "EB56";    // KDDI
        case 0xFE33E: return "EABE";    // KDDI
        case 0xFE33F: return "EABC";    // KDDI
        case 0xFE340: return "EAB9";    // KDDI
        case 0xFE341: return "EA5E";    // KDDI
        case 0xFE342: return "EABD";    // KDDI
        case 0xFE343: return "EAB8";    // KDDI
        case 0xFE344: return "EA5F";    // KDDI
        case 0xFE345: return "EA5F";    // KDDI
        case 0xFE346: return "EA04";    // KDDI
        case 0xFE347: return "EA5C";    // KDDI
        case 0xFE348: return "EB5A";    // KDDI
        case 0xFE349: return "EB78";    // KDDI
        case 0xFE34A: return "EB5C";    // KDDI
        case 0xFE34B: return "EB59";    // KDDI
        case 0xFE34C: return "EB5E";    // KDDI
        case 0xFE34D: return "EB61";    // KDDI
        case 0xFE34E: return "EB57";    // KDDI
        case 0xFE34F: return "EB63";    // KDDI
        case 0xFE350: return "EB5F";    // KDDI
        case 0xFE351: return "EAD0";    // KDDI
        case 0xFE352: return "EAD1";    // KDDI
        case 0xFE353: return "EAD2";    // KDDI
        case 0xFE354: return "EB49";    // KDDI
        case 0xFE355: return "EB4A";    // KDDI
        case 0xFE356: return "EB4B";    // KDDI
        case 0xFE357: return "EB7E";    // KDDI
        case 0xFE358: return "EB7F";    // KDDI
        case 0xFE359: return "EB80";    // KDDI
        case 0xFE35A: return "EB81";    // KDDI
        case 0xFE35B: return "EACB";    // KDDI
        case 0xFE4B0: return "E970";    // KDDI
        case 0xFE4B1: return "EB02";    // KDDI
        case 0xFE4B2: return "E99C";    // KDDI
        case 0xFE4B3: return "EA77";    // KDDI
        case 0xFE4B4: return "EA78";    // KDDI
        case 0xFE4B5: return "E9D4";    // KDDI
        case 0xFE4B6: return "E9CD";    // KDDI
        case 0xFE4B7: return "EA7A";    // KDDI
        case 0xFE4B8: return "EAEC";    // KDDI
        case 0xFE4B9: return "E9CE";    // KDDI
        case 0xFE4BA: return "EA79";    // KDDI
        case 0xFE4BB: return "EA54";    // KDDI
        case 0xFE4BC: return "EA68";    // KDDI
        case 0xFE4BD: return "EAEF";    // KDDI
        case 0xFE4BE: return "EAF0";    // KDDI
        case 0xFE4BF: return "EAF1";    // KDDI
        case 0xFE4C0: return "EAF2";    // KDDI
        case 0xFE4C1: return "E9D3";    // KDDI
        case 0xFE4C2: return "E9E1";    // KDDI
        case 0xFE4C3: return "EA56";    // KDDI
        case 0xFE4C4: return "E9E4";    // KDDI
        case 0xFE4C7: return "E9D6";    // KDDI
        case 0xFE4C8: return "EB65";    // KDDI
        case 0xFE4C9: return "E998";    // KDDI
        case 0xFE4CA: return "EA64";    // KDDI
        case 0xFE4CB: return "E97B";    // KDDI
        case 0xFE4CC: return "EA50";    // KDDI
        case 0xFE4CD: return "EB24";    // KDDI
        case 0xFE4CE: return "E974";    // KDDI
        case 0xFE4CF: return "EA4F";    // KDDI
        case 0xFE4D0: return "EB70";    // KDDI
        case 0xFE4D1: return "EA62";    // KDDI
        case 0xFE4D2: return "EA62";    // KDDI
        case 0xFE4D3: return "EA8C";    // KDDI
        case 0xFE4D4: return "EA97";    // KDDI
        case 0xFE4D5: return "EB64";    // KDDI
        case 0xFE4D6: return "E97C";    // KDDI
        case 0xFE4D7: return "E97C";    // KDDI
        case 0xFE4D8: return "EA98";    // KDDI
        case 0xFE4D9: return "EA9C";    // KDDI
        case 0xFE4DA: return "EA9D";    // KDDI
        case 0xFE4DB: return "EA2D";    // KDDI
        case 0xFE4DC: return "EA22";    // KDDI
        case 0xFE4DD: return "E9E9";    // KDDI
        case 0xFE4DF: return "EA75";    // KDDI
        case 0xFE4E0: return "E90E";    // KDDI
        case 0xFE4E1: return "E957";    // KDDI
        case 0xFE4E2: return "E96D";    // KDDI
        case 0xFE4E3: return "E98B";    // KDDI
        case 0xFE4E4: return "EB54";    // KDDI
        case 0xFE4E5: return "E9ED";    // KDDI
        case 0xFE4E6: return "E95A";    // KDDI
        case 0xFE4E7: return "EAF3";    // KDDI
        case 0xFE4E8: return "EB07";    // KDDI
        case 0xFE4E9: return "EB08";    // KDDI
        case 0xFE4EA: return "EB09";    // KDDI
        case 0xFE4EB: return "EA6E";    // KDDI
        case 0xFE4EC: return "EA6F";    // KDDI
        case 0xFE4ED: return "EB0A";    // KDDI
        case 0xFE4EE: return "EB0B";    // KDDI
        case 0xFE4EF: return "E95E";    // KDDI
        case 0xFE4F0: return "E953";    // KDDI
        case 0xFE4F2: return "E930";    // KDDI
        case 0xFE4F4: return "EA1B";    // KDDI
        case 0xFE4F5: return "EA28";    // KDDI
        case 0xFE4F6: return "EA0D";    // KDDI
        case 0xFE4F7: return "EA88";    // KDDI
        case 0xFE4F8: return "EA88";    // KDDI
        case 0xFE4F9: return "E96F";    // KDDI
        case 0xFE4FA: return "E972";    // KDDI
        case 0xFE4FB: return "E982";    // KDDI
        case 0xFE4FC: return "E987";    // KDDI
        case 0xFE4FD: return "E988";    // KDDI
        case 0xFE4FE: return "E9A2";    // KDDI
        case 0xFE4FF: return "E961";    // KDDI
        case 0xFE500: return "E964";    // KDDI
        case 0xFE501: return "E965";    // KDDI
        case 0xFE502: return "E966";    // KDDI
        case 0xFE503: return "E993";    // KDDI
        case 0xFE504: return "E991";    // KDDI
        case 0xFE505: return "EA71";    // KDDI
        case 0xFE506: return "E9CF";    // KDDI
        case 0xFE507: return "E9CF";    // KDDI
        case 0xFE508: return "E9CF";    // KDDI
        case 0xFE509: return "EA30";    // KDDI
        case 0xFE50A: return "EA93";    // KDDI
        case 0xFE50B: return "EB1F";    // KDDI
        case 0xFE50C: return "EB20";    // KDDI
        case 0xFE50D: return "EB22";    // KDDI
        case 0xFE50E: return "EB21";    // KDDI
        case 0xFE50F: return "EA38";    // KDDI
        case 0xFE510: return "E990";    // KDDI
        case 0xFE511: return "EA39";    // KDDI
        case 0xFE512: return "E9EA";    // KDDI
        case 0xFE513: return "EAE9";    // KDDI
        case 0xFE514: return "EA72";    // KDDI
        case 0xFE515: return "EA65";    // KDDI
        case 0xFE516: return "EA94";    // KDDI
        case 0xFE517: return "EA95";    // KDDI
        case 0xFE518: return "EADC";    // KDDI
        case 0xFE519: return "EADD";    // KDDI
        case 0xFE51A: return "EADE";    // KDDI
        case 0xFE51B: return "EADF";    // KDDI
        case 0xFE51C: return "EAE0";    // KDDI
        case 0xFE51D: return "EAE4";    // KDDI
        case 0xFE51E: return "EAE6";    // KDDI
        case 0xFE51F: return "EAE7";    // KDDI
        case 0xFE520: return "E9E6";    // KDDI
        case 0xFE521: return "EB36";    // KDDI
        case 0xFE522: return "EA34";    // KDDI
        case 0xFE523: return "E955";    // KDDI
        case 0xFE524: return "E99B";    // KDDI
        case 0xFE525: return "E9A1";    // KDDI
        case 0xFE526: return "EB01";    // KDDI
        case 0xFE527: return "EA8B";    // KDDI
        case 0xFE528: return "E9A6";    // KDDI
        case 0xFE529: return "E96C";    // KDDI
        case 0xFE52A: return "E997";    // KDDI
        case 0xFE52B: return "EB5B";    // KDDI
        case 0xFE52C: return "E981";    // KDDI
        case 0xFE52D: return "EB03";    // KDDI
        case 0xFE52E: return "E981";    // KDDI
        case 0xFE52F: return "E90D";    // KDDI
        case 0xFE530: return "E90D";    // KDDI
        case 0xFE531: return "E9D2";    // KDDI
        case 0xFE532: return "E956";    // KDDI
        case 0xFE533: return "E999";    // KDDI
        case 0xFE534: return "E99A";    // KDDI
        case 0xFE535: return "E9A5";    // KDDI
        case 0xFE536: return "EAFC";    // KDDI
        case 0xFE538: return "EA51";    // KDDI
        case 0xFE539: return "E995";    // KDDI
        case 0xFE53A: return "E98F";    // KDDI
        case 0xFE53B: return "EA67";    // KDDI
        case 0xFE53C: return "E97E";    // KDDI
        case 0xFE53D: return "E93B";    // KDDI
        case 0xFE53E: return "E968";    // KDDI
        case 0xFE53F: return "E931";    // KDDI
        case 0xFE540: return "E938";    // KDDI
        case 0xFE541: return "E967";    // KDDI
        case 0xFE542: return "E943";    // KDDI
        case 0xFE543: return "E94F";    // KDDI
        case 0xFE544: return "E954";    // KDDI
        case 0xFE545: return "E979";    // KDDI
        case 0xFE546: return "E97A";    // KDDI
        case 0xFE547: return "E95B";    // KDDI
        case 0xFE548: return "E95C";    // KDDI
        case 0xFE549: return "E969";    // KDDI
        case 0xFE54A: return "E97F";    // KDDI
        case 0xFE54B: return "E980";    // KDDI
        case 0xFE54C: return "E99F";    // KDDI
        case 0xFE54D: return "E983";    // KDDI
        case 0xFE54E: return "E989";    // KDDI
        case 0xFE54F: return "E98E";    // KDDI
        case 0xFE550: return "E99D";    // KDDI
        case 0xFE551: return "E99E";    // KDDI
        case 0xFE552: return "EB04";    // KDDI
        case 0xFE553: return "EB23";    // KDDI
        case 0xFE7D1: return "E92D";    // KDDI
        case 0xFE7D2: return "EA32";    // KDDI
        case 0xFE7D3: return "E9DC";    // KDDI
        case 0xFE7D4: return "E9DB";    // KDDI
        case 0xFE7D5: return "EAA5";    // KDDI
        case 0xFE7D6: return "EA33";    // KDDI
        case 0xFE7D7: return "E9DE";    // KDDI
        case 0xFE7D8: return "E9DD";    // KDDI
        case 0xFE7D9: return "E9DA";    // KDDI
        case 0xFE7DA: return "EB3A";    // KDDI
        case 0xFE7DB: return "EA6C";    // KDDI
        case 0xFE7DC: return "E9F8";    // KDDI
        case 0xFE7DD: return "E960";    // KDDI
        case 0xFE7DE: return "EAD7";    // KDDI
        case 0xFE7DF: return "E9AC";    // KDDI
        case 0xFE7E0: return "EA55";    // KDDI
        case 0xFE7E1: return "EA55";    // KDDI
        case 0xFE7E2: return "E9D9";    // KDDI
        case 0xFE7E3: return "E9D9";    // KDDI
        case 0xFE7E4: return "E97D";    // KDDI
        case 0xFE7E5: return "E97D";    // KDDI
        case 0xFE7E6: return "E9D8";    // KDDI
        case 0xFE7E7: return "E9D1";    // KDDI
        case 0xFE7E8: return "EA7B";    // KDDI
        case 0xFE7E9: return "E9A8";    // KDDI
        case 0xFE7EA: return "E9A9";    // KDDI
        case 0xFE7EB: return "E9D7";    // KDDI
        case 0xFE7EC: return "EB66";    // KDDI
        case 0xFE7ED: return "EA61";    // KDDI
        case 0xFE7EE: return "E9A9";    // KDDI
        case 0xFE7EF: return "E97D";    // KDDI
        case 0xFE7F0: return "EB6B";    // KDDI
        case 0xFE7F1: return "E994";    // KDDI
        case 0xFE7F2: return "EAD8";    // KDDI
        case 0xFE7F3: return "EAD9";    // KDDI
        case 0xFE7F4: return "EADA";    // KDDI
        case 0xFE7F5: return "E9D5";    // KDDI
        case 0xFE7F6: return "E9D0";    // KDDI
        case 0xFE7F7: return "E963";    // KDDI
        case 0xFE7F8: return "EA70";    // KDDI
        case 0xFE7F9: return "EB6C";    // KDDI
        case 0xFE7FA: return "E9E0";    // KDDI
        case 0xFE7FB: return "EA69";    // KDDI
        case 0xFE7FD: return "E9DF";    // KDDI
        case 0xFE7FE: return "EADB";    // KDDI
        case 0xFE7FF: return "EB3B";    // KDDI
        case 0xFE800: return "EA21";    // KDDI
        case 0xFE801: return "E96E";    // KDDI
        case 0xFE802: return "E96E";    // KDDI
        case 0xFE803: return "EA26";    // KDDI
        case 0xFE804: return "EA35";    // KDDI
        case 0xFE805: return "EAEE";    // KDDI
        case 0xFE806: return "EA37";    // KDDI
        case 0xFE807: return "E96A";    // KDDI
        case 0xFE808: return "E9E2";    // KDDI
        case 0xFE809: return "EA36";    // KDDI
        case 0xFE80A: return "E9E8";    // KDDI
        case 0xFE80B: return "EA6A";    // KDDI
        case 0xFE80C: return "E9E7";    // KDDI
        case 0xFE80D: return "E9E5";    // KDDI
        case 0xFE80E: return "EAD6";    // KDDI
        case 0xFE80F: return "E9AA";    // KDDI
        case 0xFE810: return "EB3C";    // KDDI
        case 0xFE811: return "EB67";    // KDDI
        case 0xFE812: return "EB68";    // KDDI
        case 0xFE813: return "EA57";    // KDDI
        case 0xFE814: return "EA23";    // KDDI
        case 0xFE816: return "EA24";    // KDDI
        case 0xFE817: return "EB39";    // KDDI
        case 0xFE818: return "EAD5";    // KDDI
        case 0xFE819: return "EA25";    // KDDI
        case 0xFE81A: return "EAC5";    // KDDI
        case 0xFE81C: return "EA20";    // KDDI
        case 0xFE81D: return "EA2C";    // KDDI
        case 0xFE81E: return "EA2C";    // KDDI
        case 0xFE81F: return "EA52";    // KDDI
        case 0xFE820: return "E973";    // KDDI
        case 0xFE821: return "E90D";    // KDDI
        case 0xFE822: return "E9AB";    // KDDI
        case 0xFE823: return "EA11";    // KDDI
        case 0xFE824: return "EB71";    // KDDI
        case 0xFE825: return "E948";    // KDDI
        case 0xFE826: return "E948";    // KDDI
        case 0xFE827: return "EA63";    // KDDI
        case 0xFE828: return "EA8E";    // KDDI
        case 0xFE829: return "EAD3";    // KDDI
        case 0xFE82A: return "EA54";    // KDDI
        case 0xFE82C: return "EB7D";    // KDDI
        case 0xFE82E: return "E9B4";    // KDDI
        case 0xFE82F: return "E9B5";    // KDDI
        case 0xFE830: return "E9B6";    // KDDI
        case 0xFE831: return "E9B7";    // KDDI
        case 0xFE832: return "E9B8";    // KDDI
        case 0xFE833: return "E9B9";    // KDDI
        case 0xFE834: return "E9BA";    // KDDI
        case 0xFE835: return "E9BB";    // KDDI
        case 0xFE836: return "E9BC";    // KDDI
        case 0xFE837: return "EA45";    // KDDI
        case 0xFE838: return "EA7D";    // KDDI
        case 0xFE839: return "EA89";    // KDDI
        case 0xFE83A: return "EA8A";    // KDDI
        case 0xFE83B: return "E9BD";    // KDDI
        case 0xFE960: return "E9F5";    // KDDI
        case 0xFE961: return "E9F4";    // KDDI
        case 0xFE962: return "E9EF";    // KDDI
        case 0xFE963: return "EA4D";    // KDDI
        case 0xFE964: return "EAA8";    // KDDI
        case 0xFE965: return "E9F0";    // KDDI
        case 0xFE966: return "EAA9";    // KDDI
        case 0xFE967: return "EAAA";    // KDDI
        case 0xFE968: return "EAAB";    // KDDI
        case 0xFE969: return "EAAC";    // KDDI
        case 0xFE96A: return "EAAD";    // KDDI
        case 0xFE96B: return "EAAE";    // KDDI
        case 0xFE96C: return "EAAF";    // KDDI
        case 0xFE96D: return "EAB0";    // KDDI
        case 0xFE96E: return "EAB1";    // KDDI
        case 0xFE96F: return "EAB6";    // KDDI
        case 0xFE970: return "EAB7";    // KDDI
        case 0xFE971: return "EAE3";    // KDDI
        case 0xFE972: return "E9A0";    // KDDI
        case 0xFE973: return "EA13";    // KDDI
        case 0xFE974: return "EB33";    // KDDI
        case 0xFE975: return "EB34";    // KDDI
        case 0xFE976: return "EB35";    // KDDI
        case 0xFE977: return "EB43";    // KDDI
        case 0xFE978: return "EB44";    // KDDI
        case 0xFE979: return "EB45";    // KDDI
        case 0xFE97A: return "EB46";    // KDDI
        case 0xFE97B: return "EB47";    // KDDI
        case 0xFE97C: return "EB48";    // KDDI
        case 0xFE97D: return "EB4F";    // KDDI
        case 0xFE97E: return "EB52";    // KDDI
        case 0xFE97F: return "EB69";    // KDDI
        case 0xFE980: return "E992";    // KDDI
        case 0xFE981: return "E95D";    // KDDI
        case 0xFE982: return "E934";    // KDDI
        case 0xFE983: return "E941";    // KDDI
        case 0xFE984: return "EAA7";    // KDDI
        case 0xFE985: return "EA90";    // KDDI
        case 0xFE986: return "E90C";    // KDDI
        case 0xFE987: return "EA91";    // KDDI
        case 0xFE988: return "EB37";    // KDDI
        case 0xFEAF0: return "E946";    // KDDI
        case 0xFEAF1: return "E92B";    // KDDI
        case 0xFEAF2: return "E92A";    // KDDI
        case 0xFEAF3: return "E947";    // KDDI
        case 0xFEAF4: return "EB26";    // KDDI
        case 0xFEAF5: return "EB27";    // KDDI
        case 0xFEAF6: return "EB73";    // KDDI
        case 0xFEAF7: return "EB74";    // KDDI
        case 0xFEAF8: return "E91D";    // KDDI
        case 0xFEAF9: return "E91E";    // KDDI
        case 0xFEAFA: return "E93F";    // KDDI
        case 0xFEAFB: return "E940";    // KDDI
        case 0xFEAFC: return "E906";    // KDDI
        case 0xFEAFD: return "E905";    // KDDI
        case 0xFEAFE: return "E908";    // KDDI
        case 0xFEAFF: return "E907";    // KDDI
        case 0xFEB00: return "E920";    // KDDI
        case 0xFEB01: return "E921";    // KDDI
        case 0xFEB02: return "E922";    // KDDI
        case 0xFEB03: return "E923";    // KDDI
        case 0xFEB04: return "E902";    // KDDI
        case 0xFEB05: return "EB28";    // KDDI
        case 0xFEB06: return "EB29";    // KDDI
        case 0xFEB08: return "EB2A";    // KDDI
        case 0xFEB09: return "E903";    // KDDI
        case 0xFEB0A: return "E903";    // KDDI
        case 0xFEB0B: return "E902";    // KDDI
        case 0xFEB0C: return "E933";    // KDDI
        case 0xFEB0D: return "EB6E";    // KDDI
        case 0xFEB0E: return "EA09";    // KDDI
        case 0xFEB0F: return "EA0A";    // KDDI
        case 0xFEB10: return "EA9F";    // KDDI
        case 0xFEB11: return "EB6E";    // KDDI
        case 0xFEB12: return "EA10";    // KDDI
        case 0xFEB13: return "EAA0";    // KDDI
        case 0xFEB14: return "EAA1";    // KDDI
        case 0xFEB15: return "EAA2";    // KDDI
        case 0xFEB16: return "EAA3";    // KDDI
        case 0xFEB17: return "EB4D";    // KDDI
        case 0xFEB18: return "EA48";    // KDDI
        case 0xFEB19: return "E933";    // KDDI
        case 0xFEB1A: return "EA9E";    // KDDI
        case 0xFEB1B: return "EA3A";    // KDDI
        case 0xFEB1C: return "EA3B";    // KDDI
        case 0xFEB1D: return "EA3C";    // KDDI
        case 0xFEB1E: return "E9B0";    // KDDI
        case 0xFEB1F: return "E9B1";    // KDDI
        case 0xFEB20: return "E9B2";    // KDDI
        case 0xFEB21: return "EA2B";    // KDDI
        case 0xFEB22: return "EB25";    // KDDI
        case 0xFEB23: return "E901";    // KDDI
        case 0xFEB25: return "EA7C";    // KDDI
        case 0xFEB26: return "E962";    // KDDI
        case 0xFEB27: return "EA46";    // KDDI
        case 0xFEB29: return "E951";    // KDDI
        case 0xFEB2A: return "E936";    // KDDI
        case 0xFEB2B: return "EA17";    // KDDI
        case 0xFEB2C: return "EB72";    // KDDI
        case 0xFEB2D: return "E952";    // KDDI
        case 0xFEB2F: return "EA83";    // KDDI
        case 0xFEB31: return "EA82";    // KDDI
        case 0xFEB32: return "EA6B";    // KDDI
        case 0xFEB35: return "EB11";    // KDDI
        case 0xFEB36: return "EA4E";    // KDDI
        case 0xFEB37: return "EA2F";    // KDDI
        case 0xFEB38: return "EA7E";    // KDDI
        case 0xFEB3D: return "EA1D";    // KDDI
        case 0xFEB3E: return "EA7F";    // KDDI
        case 0xFEB3F: return "EA80";    // KDDI
        case 0xFEB40: return "EA84";    // KDDI
        case 0xFEB41: return "EA85";    // KDDI
        case 0xFEB43: return "EA92";    // KDDI
        case 0xFEB44: return "EAA6";    // KDDI
        case 0xFEB45: return "E93D";    // KDDI
        case 0xFEB46: return "E93E";    // KDDI
        case 0xFEB47: return "E90B";    // KDDI
        case 0xFEB48: return "E91F";    // KDDI
        case 0xFEB49: return "E949";    // KDDI
        case 0xFEB4A: return "E984";    // KDDI
        case 0xFEB4B: return "E9A4";    // KDDI
        case 0xFEB4C: return "E9AD";    // KDDI
        case 0xFEB4D: return "E9AE";    // KDDI
        case 0xFEB4E: return "E9AF";    // KDDI
        case 0xFEB4F: return "EA0E";    // KDDI
        case 0xFEB50: return "EAFA";    // KDDI
        case 0xFEB51: return "E91A";    // KDDI
        case 0xFEB52: return "E91B";    // KDDI
        case 0xFEB53: return "E937";    // KDDI
        case 0xFEB54: return "E942";    // KDDI
        case 0xFEB56: return "E94D";    // KDDI
        case 0xFEB57: return "EA06";    // KDDI
        case 0xFEB58: return "EA0C";    // KDDI
        case 0xFEB59: return "EA05";    // KDDI
        case 0xFEB5A: return "EA49";    // KDDI
        case 0xFEB5B: return "EA4A";    // KDDI
        case 0xFEB5C: return "EA07";    // KDDI
        case 0xFEB5D: return "EA1A";    // KDDI
        case 0xFEB5E: return "EA0F";    // KDDI
        case 0xFEB5F: return "EB55";    // KDDI
        case 0xFEB60: return "EAA4";    // KDDI
        case 0xFEB61: return "EA0B";    // KDDI
        case 0xFEB62: return "E91C";    // KDDI
        case 0xFEB63: return "E928";    // KDDI
        case 0xFEB64: return "E929";    // KDDI
        case 0xFEB65: return "E917";    // KDDI
        case 0xFEB66: return "E918";    // KDDI
        case 0xFEB67: return "E929";    // KDDI
        case 0xFEB68: return "E945";    // KDDI
        case 0xFEB69: return "E945";    // KDDI
        case 0xFEB6A: return "E94B";    // KDDI
        case 0xFEB6B: return "E926";    // KDDI
        case 0xFEB6C: return "E927";    // KDDI
        case 0xFEB6D: return "E909";    // KDDI
        case 0xFEB6E: return "E90A";    // KDDI
        case 0xFEB6F: return "E911";    // KDDI
        case 0xFEB70: return "E912";    // KDDI
        case 0xFEB71: return "E915";    // KDDI
        case 0xFEB72: return "E916";    // KDDI
        case 0xFEB73: return "E924";    // KDDI
        case 0xFEB74: return "E925";    // KDDI
        case 0xFEB75: return "E913";    // KDDI
        case 0xFEB76: return "E914";    // KDDI
        case 0xFEB77: return "E94C";    // KDDI
        case 0xFEB78: return "E958";    // KDDI
        case 0xFEB79: return "E959";    // KDDI
        case 0xFEB7A: return "EA16";    // KDDI
        case 0xFEB7B: return "EA18";    // KDDI
        case 0xFEB7C: return "EAF6";    // KDDI
        case 0xFEB7D: return "EAF7";    // KDDI
        case 0xFEB7E: return "EAF8";    // KDDI
        case 0xFEB7F: return "EAF9";    // KDDI
        case 0xFEB80: return "EB4E";    // KDDI
        case 0xFEB81: return "EA81";    // KDDI
        case 0xFEB82: return "E978";    // KDDI
        case 0xFEB83: return "E976";    // KDDI
        case 0xFEB84: return "EA44";    // KDDI
        case 0xFEB85: return "E977";    // KDDI
        case 0xFEB86: return "E98A";    // KDDI
        case 0xFEB87: return "E98A";    // KDDI
        case 0xFEB88: return "E975";    // KDDI
        case 0xFEB8A: return "EAF5";    // KDDI
        case 0xFEB8B: return "EAFB";    // KDDI
        case 0xFEB8C: return "EAFD";    // KDDI
        case 0xFEB8D: return "EAFE";    // KDDI
        case 0xFEB8E: return "EAFF";    // KDDI
        case 0xFEB8F: return "EB00";    // KDDI
        case 0xFEB90: return "EB05";    // KDDI
        case 0xFEB91: return "EB06";    // KDDI
        case 0xFEB92: return "EB6A";    // KDDI
        case 0xFEB93: return "EB7C";    // KDDI
        case 0xFEB94: return "EA3F";    // KDDI
        case 0xFEB95: return "EA40";    // KDDI
        case 0xFEB96: return "EA19";    // KDDI
        case 0xFEB97: return "EA1F";    // KDDI
        case 0xFEB98: return "EA1C";    // KDDI
        case 0xFEB99: return "EA86";    // KDDI
        case 0xFEB9A: return "EA87";    // KDDI
        case 0xFEB9B: return "E98C";    // KDDI
        case 0xFEB9C: return "E98D";    // KDDI
        case 0xFEB9D: return "EACF";    // KDDI
        case 0xFEB9E: return "EACC";    // KDDI
        case 0xFEB9F: return "EACD";    // KDDI
        case 0xFEBA0: return "EACE";    // KDDI
        case 0xFEBA1: return "EACF";    // KDDI

            /* unknown */
        case 0xFEE1C: return "E517";    // KDDI ?
        case 0xFEE33: return "E5BC";    // KDDI ?
        case 0xFEE40: return "E577";    // KDDI ?
        case 0xFEE41: return "E5B2";    // KDDI ?
        case 0xFEE42: return "E264";    // KDDI ?
        case 0xFEE43: return "E328";    // KDDI ?
        case 0xFEE44: return "E335";    // KDDI ?
        case 0xFEE45: return "E33D";    // KDDI ?
        case 0xFEE46: return "E33E";    // KDDI ?
        case 0xFEE47: return "E33F";    // KDDI ?
        case 0xFEE48: return "E340";    // KDDI ?
        case 0xFEE49: return "E341";    // KDDI ?
        case 0xFEE4A: return "E342";    // KDDI ?
        default: return null;
        }
    }

    private static String getEmojiForCodePointSoftBank(int codePoint) {

        switch (codePoint) {
        case 0xFE000: return "E04A";    // SoftBank
        case 0xFE001: return "E049";    // SoftBank
        case 0xFE002: return "E04B";    // SoftBank
        case 0xFE003: return "E048";    // SoftBank
        case 0xFE004: return "E13D";    // SoftBank
        case 0xFE005: return "E443";    // SoftBank
        case 0xFE007: return "E43C";    // SoftBank
        case 0xFE008: return "E44B";    // SoftBank
        case 0xFE009: return "E04D";    // SoftBank
        case 0xFE00A: return "E449";    // SoftBank
        case 0xFE00B: return "E146";    // SoftBank
        case 0xFE00C: return "E44A";    // SoftBank
        case 0xFE00D: return "E44C";    // SoftBank
        case 0xFE00F: return "E04AE049";    // SoftBank
        case 0xFE010: return "E44B";    // SoftBank
        case 0xFE012: return "E04C";    // SoftBank
        case 0xFE013: return "E04C";    // SoftBank
        case 0xFE014: return "E04C";    // SoftBank
        case 0xFE016: return "E04C";    // SoftBank
        case 0xFE017: return "E446";    // SoftBank
        case 0xFE01E: return "E024";    // SoftBank
        case 0xFE01F: return "E025";    // SoftBank
        case 0xFE020: return "E026";    // SoftBank
        case 0xFE021: return "E027";    // SoftBank
        case 0xFE022: return "E028";    // SoftBank
        case 0xFE023: return "E029";    // SoftBank
        case 0xFE024: return "E02A";    // SoftBank
        case 0xFE025: return "E02B";    // SoftBank
        case 0xFE026: return "E02C";    // SoftBank
        case 0xFE027: return "E02D";    // SoftBank
        case 0xFE028: return "E02E";    // SoftBank
        case 0xFE029: return "E02F";    // SoftBank
        case 0xFE02A: return "E02D";    // SoftBank
        case 0xFE02B: return "E23F";    // SoftBank
        case 0xFE02C: return "E240";    // SoftBank
        case 0xFE02D: return "E241";    // SoftBank
        case 0xFE02E: return "E242";    // SoftBank
        case 0xFE02F: return "E243";    // SoftBank
        case 0xFE030: return "E244";    // SoftBank
        case 0xFE031: return "E245";    // SoftBank
        case 0xFE032: return "E246";    // SoftBank
        case 0xFE033: return "E247";    // SoftBank
        case 0xFE034: return "E248";    // SoftBank
        case 0xFE035: return "E249";    // SoftBank
        case 0xFE036: return "E24A";    // SoftBank
        case 0xFE037: return "E24B";    // SoftBank
        case 0xFE038: return "E43E";    // SoftBank
        case 0xFE03B: return "E44B";    // SoftBank
        case 0xFE03C: return "E110";    // SoftBank
        case 0xFE03D: return "E304";    // SoftBank
        case 0xFE03E: return "E110";    // SoftBank
        case 0xFE03F: return "E118";    // SoftBank
        case 0xFE040: return "E030";    // SoftBank
        case 0xFE041: return "E032";    // SoftBank
        case 0xFE042: return "E119";    // SoftBank
        case 0xFE043: return "E447";    // SoftBank
        case 0xFE044: return "E209";    // SoftBank
        case 0xFE045: return "E303";    // SoftBank
        case 0xFE046: return "E305";    // SoftBank
        case 0xFE047: return "E307";    // SoftBank
        case 0xFE048: return "E308";    // SoftBank
        case 0xFE049: return "E444";    // SoftBank
        case 0xFE04D: return "E305";    // SoftBank
        case 0xFE04E: return "E110";    // SoftBank
        case 0xFE051: return "E345";    // SoftBank
        case 0xFE052: return "E346";    // SoftBank
        case 0xFE053: return "E347";    // SoftBank
        case 0xFE054: return "E348";    // SoftBank
        case 0xFE055: return "E349";    // SoftBank
        case 0xFE056: return "E34A";    // SoftBank
        case 0xFE05B: return "E345";    // SoftBank
        case 0xFE190: return "E419";    // SoftBank
        case 0xFE191: return "E41B";    // SoftBank
        case 0xFE192: return "E41A";    // SoftBank
        case 0xFE193: return "E41C";    // SoftBank
        case 0xFE194: return "E409";    // SoftBank
        case 0xFE195: return "E31C";    // SoftBank
        case 0xFE196: return "E31D";    // SoftBank
        case 0xFE197: return "E31E";    // SoftBank
        case 0xFE198: return "E31F";    // SoftBank
        case 0xFE199: return "E320";    // SoftBank
        case 0xFE19B: return "E001";    // SoftBank
        case 0xFE19C: return "E002";    // SoftBank
        case 0xFE19D: return "E004";    // SoftBank
        case 0xFE19E: return "E005";    // SoftBank
        case 0xFE1A0: return "E428";    // SoftBank
        case 0xFE1A1: return "E152";    // SoftBank
        case 0xFE1A2: return "E429";    // SoftBank
        case 0xFE1A4: return "E515";    // SoftBank
        case 0xFE1A5: return "E516";    // SoftBank
        case 0xFE1A6: return "E517";    // SoftBank
        case 0xFE1A7: return "E518";    // SoftBank
        case 0xFE1A8: return "E519";    // SoftBank
        case 0xFE1A9: return "E51A";    // SoftBank
        case 0xFE1AA: return "E51B";    // SoftBank
        case 0xFE1AB: return "E51C";    // SoftBank
        case 0xFE1AE: return "E11B";    // SoftBank
        case 0xFE1AF: return "E04E";    // SoftBank
        case 0xFE1B0: return "E10C";    // SoftBank
        case 0xFE1B1: return "E12B";    // SoftBank
        case 0xFE1B2: return "E11A";    // SoftBank
        case 0xFE1B3: return "E11C";    // SoftBank
        case 0xFE1B4: return "E253";    // SoftBank
        case 0xFE1B5: return "E51E";    // SoftBank
        case 0xFE1B6: return "E51F";    // SoftBank
        case 0xFE1B7: return "E052";    // SoftBank
        case 0xFE1B8: return "E04F";    // SoftBank
        case 0xFE1BA: return "E523";    // SoftBank
        case 0xFE1BB: return "E523";    // SoftBank
        case 0xFE1BC: return "E055";    // SoftBank
        case 0xFE1BD: return "E019";    // SoftBank
        case 0xFE1BE: return "E01A";    // SoftBank
        case 0xFE1BF: return "E10B";    // SoftBank
        case 0xFE1C0: return "E050";    // SoftBank
        case 0xFE1C1: return "E051";    // SoftBank
        case 0xFE1C2: return "E053";    // SoftBank
        case 0xFE1C3: return "E054";    // SoftBank
        case 0xFE1C4: return "E109";    // SoftBank
        case 0xFE1C5: return "E10A";    // SoftBank
        case 0xFE1C6: return "E441";    // SoftBank
        case 0xFE1C7: return "E520";    // SoftBank
        case 0xFE1C8: return "E521";    // SoftBank
        case 0xFE1C9: return "E522";    // SoftBank
        case 0xFE1CA: return "E524";    // SoftBank
        case 0xFE1CB: return "E525";    // SoftBank
        case 0xFE1CC: return "E526";    // SoftBank
        case 0xFE1CD: return "E527";    // SoftBank
        case 0xFE1CE: return "E528";    // SoftBank
        case 0xFE1CF: return "E529";    // SoftBank
        case 0xFE1D0: return "E52A";    // SoftBank
        case 0xFE1D1: return "E52B";    // SoftBank
        case 0xFE1D2: return "E52C";    // SoftBank
        case 0xFE1D3: return "E52D";    // SoftBank
        case 0xFE1D4: return "E52E";    // SoftBank
        case 0xFE1D5: return "E52F";    // SoftBank
        case 0xFE1D6: return "E530";    // SoftBank
        case 0xFE1D7: return "E531";    // SoftBank
        case 0xFE1D8: return "E052";    // SoftBank
        case 0xFE1D9: return "E019";    // SoftBank
        case 0xFE1DB: return "E536";    // SoftBank
        case 0xFE1DD: return "E523";    // SoftBank
        case 0xFE1E0: return "E10B";    // SoftBank
        case 0xFE320: return "E059";    // SoftBank
        case 0xFE321: return "E403";    // SoftBank
        case 0xFE322: return "E410";    // SoftBank
        case 0xFE323: return "E058";    // SoftBank
        case 0xFE324: return "E406";    // SoftBank
        case 0xFE325: return "E40F";    // SoftBank
        case 0xFE326: return "E40E";    // SoftBank
        case 0xFE327: return "E106";    // SoftBank
        case 0xFE328: return "E404";    // SoftBank
        case 0xFE329: return "E105";    // SoftBank
        case 0xFE32A: return "E409";    // SoftBank
        case 0xFE32B: return "E056";    // SoftBank
        case 0xFE32C: return "E418";    // SoftBank
        case 0xFE32D: return "E417";    // SoftBank
        case 0xFE32E: return "E40C";    // SoftBank
        case 0xFE32F: return "E40D";    // SoftBank
        case 0xFE330: return "E057";    // SoftBank
        case 0xFE331: return "E415E331";    // SoftBank
        case 0xFE332: return "E40A";    // SoftBank
        case 0xFE333: return "E404";    // SoftBank
        case 0xFE334: return "E412";    // SoftBank
        case 0xFE335: return "E056";    // SoftBank
        case 0xFE336: return "E414";    // SoftBank
        case 0xFE337: return "E056";    // SoftBank
        case 0xFE338: return "E415";    // SoftBank
        case 0xFE339: return "E413";    // SoftBank
        case 0xFE33A: return "E411";    // SoftBank
        case 0xFE33B: return "E40B";    // SoftBank
        case 0xFE33C: return "E406";    // SoftBank
        case 0xFE33D: return "E416";    // SoftBank
        case 0xFE33E: return "E40A";    // SoftBank
        case 0xFE33F: return "E407";    // SoftBank
        case 0xFE340: return "E403";    // SoftBank
        case 0xFE341: return "E107";    // SoftBank
        case 0xFE342: return "E408";    // SoftBank
        case 0xFE343: return "E402";    // SoftBank
        case 0xFE344: return "E108";    // SoftBank
        case 0xFE345: return "E401";    // SoftBank
        case 0xFE346: return "E406";    // SoftBank
        case 0xFE347: return "E405";    // SoftBank
        case 0xFE348: return "E057";    // SoftBank
        case 0xFE349: return "E404";    // SoftBank
        case 0xFE34A: return "E412";    // SoftBank
        case 0xFE34B: return "E418";    // SoftBank
        case 0xFE34C: return "E106";    // SoftBank
        case 0xFE34D: return "E413";    // SoftBank
        case 0xFE34E: return "E416";    // SoftBank
        case 0xFE34F: return "E404";    // SoftBank
        case 0xFE350: return "E403";    // SoftBank
        case 0xFE351: return "E423";    // SoftBank
        case 0xFE352: return "E424";    // SoftBank
        case 0xFE353: return "E426";    // SoftBank
        case 0xFE357: return "E012";    // SoftBank
        case 0xFE358: return "E427";    // SoftBank
        case 0xFE359: return "E403";    // SoftBank
        case 0xFE35A: return "E416";    // SoftBank
        case 0xFE35B: return "E41D";    // SoftBank
        case 0xFE4B0: return "E036";    // SoftBank
        case 0xFE4B1: return "E036";    // SoftBank
        case 0xFE4B2: return "E038";    // SoftBank
        case 0xFE4B3: return "E153";    // SoftBank
        case 0xFE4B4: return "E155";    // SoftBank
        case 0xFE4B5: return "E14D";    // SoftBank
        case 0xFE4B6: return "E154";    // SoftBank
        case 0xFE4B7: return "E158";    // SoftBank
        case 0xFE4B8: return "E501";    // SoftBank
        case 0xFE4B9: return "E156";    // SoftBank
        case 0xFE4BA: return "E157";    // SoftBank
        case 0xFE4BB: return "E037";    // SoftBank
        case 0xFE4BC: return "E121";    // SoftBank
        case 0xFE4BD: return "E504";    // SoftBank
        case 0xFE4BE: return "E505";    // SoftBank
        case 0xFE4BF: return "E506";    // SoftBank
        case 0xFE4C0: return "E508";    // SoftBank
        case 0xFE4C1: return "E202";    // SoftBank
        case 0xFE4C2: return "E30B";    // SoftBank
        case 0xFE4C3: return "E03B";    // SoftBank
        case 0xFE4C4: return "E509";    // SoftBank
        case 0xFE4C5: return "E50A";    // SoftBank
        case 0xFE4C6: return "E51D";    // SoftBank
        case 0xFE4CA: return "E116";    // SoftBank
        case 0xFE4CC: return "E007";    // SoftBank
        case 0xFE4CD: return "E007";    // SoftBank
        case 0xFE4CF: return "E006";    // SoftBank
        case 0xFE4D1: return "E10E";    // SoftBank
        case 0xFE4D2: return "E031";    // SoftBank
        case 0xFE4D3: return "E302";    // SoftBank
        case 0xFE4D4: return "E318";    // SoftBank
        case 0xFE4D5: return "E319";    // SoftBank
        case 0xFE4D6: return "E13E";    // SoftBank
        case 0xFE4D7: return "E31A";    // SoftBank
        case 0xFE4D8: return "E31B";    // SoftBank
        case 0xFE4D9: return "E321";    // SoftBank
        case 0xFE4DA: return "E322";    // SoftBank
        case 0xFE4DB: return "E006";    // SoftBank
        case 0xFE4DD: return "E12F";    // SoftBank
        case 0xFE4DE: return "E149";    // SoftBank
        case 0xFE4DF: return "E14A";    // SoftBank
        case 0xFE4E0: return "E12F";    // SoftBank
        case 0xFE4E3: return "E12F";    // SoftBank
        case 0xFE4E5: return "E50B";    // SoftBank
        case 0xFE4E6: return "E50C";    // SoftBank
        case 0xFE4E7: return "E50D";    // SoftBank
        case 0xFE4E8: return "E50E";    // SoftBank
        case 0xFE4E9: return "E50F";    // SoftBank
        case 0xFE4EA: return "E510";    // SoftBank
        case 0xFE4EB: return "E511";    // SoftBank
        case 0xFE4EC: return "E512";    // SoftBank
        case 0xFE4ED: return "E513";    // SoftBank
        case 0xFE4EE: return "E514";    // SoftBank
        case 0xFE4EF: return "E008";    // SoftBank
        case 0xFE4F0: return "E323";    // SoftBank
        case 0xFE4F2: return "E325";    // SoftBank
        case 0xFE4F4: return "E05A";    // SoftBank
        case 0xFE4F5: return "E113";    // SoftBank
        case 0xFE4F6: return "E11D";    // SoftBank
        case 0xFE4F7: return "E23E";    // SoftBank
        case 0xFE4F8: return "E23E";    // SoftBank
        case 0xFE4F9: return "E03D";    // SoftBank
        case 0xFE4FF: return "E148";    // SoftBank
        case 0xFE500: return "E148";    // SoftBank
        case 0xFE501: return "E148";    // SoftBank
        case 0xFE502: return "E148";    // SoftBank
        case 0xFE503: return "E148";    // SoftBank
        case 0xFE505: return "E13F";    // SoftBank
        case 0xFE506: return "E151";    // SoftBank
        case 0xFE507: return "E140";    // SoftBank
        case 0xFE508: return "E309";    // SoftBank
        case 0xFE509: return "E13B";    // SoftBank
        case 0xFE50A: return "E30F";    // SoftBank
        case 0xFE50B: return "E532";    // SoftBank
        case 0xFE50C: return "E533";    // SoftBank
        case 0xFE50D: return "E534";    // SoftBank
        case 0xFE50E: return "E535";    // SoftBank
        case 0xFE50F: return "E314";    // SoftBank
        case 0xFE510: return "E112";    // SoftBank
        case 0xFE511: return "E34B";    // SoftBank
        case 0xFE512: return "E033";    // SoftBank
        case 0xFE513: return "E448";    // SoftBank
        case 0xFE514: return "E143";    // SoftBank
        case 0xFE515: return "E117";    // SoftBank
        case 0xFE516: return "E310";    // SoftBank
        case 0xFE517: return "E312";    // SoftBank
        case 0xFE518: return "E436";    // SoftBank
        case 0xFE519: return "E438";    // SoftBank
        case 0xFE51A: return "E439";    // SoftBank
        case 0xFE51B: return "E43A";    // SoftBank
        case 0xFE51C: return "E43B";    // SoftBank
        case 0xFE51D: return "E440";    // SoftBank
        case 0xFE51E: return "E442";    // SoftBank
        case 0xFE51F: return "E445";    // SoftBank
        case 0xFE523: return "E009";    // SoftBank
        case 0xFE524: return "E009";    // SoftBank
        case 0xFE525: return "E00A";    // SoftBank
        case 0xFE526: return "E104";    // SoftBank
        case 0xFE527: return "E301";    // SoftBank
        case 0xFE528: return "E00B";    // SoftBank
        case 0xFE529: return "E103";    // SoftBank
        case 0xFE52A: return "E103";    // SoftBank
        case 0xFE52B: return "E103";    // SoftBank
        case 0xFE52C: return "E101";    // SoftBank
        case 0xFE52D: return "E101";    // SoftBank
        case 0xFE52E: return "E102";    // SoftBank
        case 0xFE52F: return "E142";    // SoftBank
        case 0xFE530: return "E317";    // SoftBank
        case 0xFE531: return "E14B";    // SoftBank
        case 0xFE535: return "E112";    // SoftBank
        case 0xFE537: return "E11F";    // SoftBank
        case 0xFE538: return "E00C";    // SoftBank
        case 0xFE539: return "E301";    // SoftBank
        case 0xFE53B: return "E11E";    // SoftBank
        case 0xFE53C: return "E316";    // SoftBank
        case 0xFE53D: return "E316";    // SoftBank
        case 0xFE53E: return "E313";    // SoftBank
        case 0xFE540: return "E301";    // SoftBank
        case 0xFE541: return "E301";    // SoftBank
        case 0xFE545: return "E148";    // SoftBank
        case 0xFE546: return "E148";    // SoftBank
        case 0xFE547: return "E148";    // SoftBank
        case 0xFE548: return "E301";    // SoftBank
        case 0xFE54A: return "E14A";    // SoftBank
        case 0xFE54B: return "E14A";    // SoftBank
        case 0xFE54D: return "E148";    // SoftBank
        case 0xFE54F: return "E148";    // SoftBank
        case 0xFE552: return "E301";    // SoftBank
        case 0xFE553: return "E536";    // SoftBank
        case 0xFE7D1: return "E016";    // SoftBank
        case 0xFE7D2: return "E014";    // SoftBank
        case 0xFE7D3: return "E015";    // SoftBank
        case 0xFE7D4: return "E018";    // SoftBank
        case 0xFE7D5: return "E013";    // SoftBank
        case 0xFE7D6: return "E42A";    // SoftBank
        case 0xFE7D7: return "E132";    // SoftBank
        case 0xFE7D9: return "E115";    // SoftBank
        case 0xFE7DA: return "E017";    // SoftBank
        case 0xFE7DB: return "E131";    // SoftBank
        case 0xFE7DC: return "E134";    // SoftBank
        case 0xFE7DD: return "E42B";    // SoftBank
        case 0xFE7DE: return "E42D";    // SoftBank
        case 0xFE7DF: return "E01E";    // SoftBank
        case 0xFE7E0: return "E434";    // SoftBank
        case 0xFE7E1: return "E434";    // SoftBank
        case 0xFE7E2: return "E435";    // SoftBank
        case 0xFE7E3: return "E01F";    // SoftBank
        case 0xFE7E4: return "E01B";    // SoftBank
        case 0xFE7E5: return "E42E";    // SoftBank
        case 0xFE7E6: return "E159";    // SoftBank
        case 0xFE7E7: return "E150";    // SoftBank
        case 0xFE7E8: return "E202";    // SoftBank
        case 0xFE7E9: return "E01D";    // SoftBank
        case 0xFE7EA: return "E01C";    // SoftBank
        case 0xFE7EB: return "E136";    // SoftBank
        case 0xFE7EC: return "E039";    // SoftBank
        case 0xFE7ED: return "E10D";    // SoftBank
        case 0xFE7EE: return "E135";    // SoftBank
        case 0xFE7EF: return "E15A";    // SoftBank
        case 0xFE7F0: return "E201";    // SoftBank
        case 0xFE7F1: return "E42F";    // SoftBank
        case 0xFE7F2: return "E430";    // SoftBank
        case 0xFE7F3: return "E431";    // SoftBank
        case 0xFE7F4: return "E432";    // SoftBank
        case 0xFE7F5: return "E03A";    // SoftBank
        case 0xFE7F6: return "E14F";    // SoftBank
        case 0xFE7F7: return "E14E";    // SoftBank
        case 0xFE7F8: return "E137";    // SoftBank
        case 0xFE7F9: return "E432";    // SoftBank
        case 0xFE7FA: return "E123";    // SoftBank
        case 0xFE7FB: return "E122";    // SoftBank
        case 0xFE7FD: return "E124";    // SoftBank
        case 0xFE7FE: return "E433";    // SoftBank
        case 0xFE7FF: return "E019";    // SoftBank
        case 0xFE800: return "E03C";    // SoftBank
        case 0xFE801: return "E03D";    // SoftBank
        case 0xFE802: return "E507";    // SoftBank
        case 0xFE803: return "E30A";    // SoftBank
        case 0xFE804: return "E502";    // SoftBank
        case 0xFE805: return "E503";    // SoftBank
        case 0xFE807: return "E125";    // SoftBank
        case 0xFE808: return "E324";    // SoftBank
        case 0xFE809: return "E503";    // SoftBank
        case 0xFE80B: return "E12D";    // SoftBank
        case 0xFE80C: return "E130";    // SoftBank
        case 0xFE80D: return "E133";    // SoftBank
        case 0xFE80E: return "E42C";    // SoftBank
        case 0xFE813: return "E03E";    // SoftBank
        case 0xFE814: return "E326";    // SoftBank
        case 0xFE815: return "E040";    // SoftBank
        case 0xFE816: return "E041";    // SoftBank
        case 0xFE818: return "E042";    // SoftBank
        case 0xFE81A: return "E326";    // SoftBank
        case 0xFE81B: return "E12C";    // SoftBank
        case 0xFE81C: return "E12A";    // SoftBank
        case 0xFE81D: return "E126";    // SoftBank
        case 0xFE81E: return "E127";    // SoftBank
        case 0xFE81F: return "E128";    // SoftBank
        case 0xFE820: return "E129";    // SoftBank
        case 0xFE821: return "E141";    // SoftBank
        case 0xFE823: return "E003";    // SoftBank
        case 0xFE824: return "E103E328";    // SoftBank
        case 0xFE825: return "E034";    // SoftBank
        case 0xFE826: return "E035";    // SoftBank
        case 0xFE827: return "E111";    // SoftBank
        case 0xFE828: return "E306";    // SoftBank
        case 0xFE829: return "E425";    // SoftBank
        case 0xFE82A: return "E43D";    // SoftBank
        case 0xFE82B: return "E211";    // SoftBank
        case 0xFE82C: return "E210";    // SoftBank
        case 0xFE82E: return "E21C";    // SoftBank
        case 0xFE82F: return "E21D";    // SoftBank
        case 0xFE830: return "E21E";    // SoftBank
        case 0xFE831: return "E21F";    // SoftBank
        case 0xFE832: return "E220";    // SoftBank
        case 0xFE833: return "E221";    // SoftBank
        case 0xFE834: return "E222";    // SoftBank
        case 0xFE835: return "E223";    // SoftBank
        case 0xFE836: return "E224";    // SoftBank
        case 0xFE837: return "E225";    // SoftBank
        case 0xFE838: return "E20B";    // SoftBank
        case 0xFE839: return "E250";    // SoftBank
        case 0xFE83A: return "E251";    // SoftBank
        case 0xFE960: return "E120";    // SoftBank
        case 0xFE961: return "E342";    // SoftBank
        case 0xFE962: return "E046";    // SoftBank
        case 0xFE963: return "E340";    // SoftBank
        case 0xFE964: return "E339";    // SoftBank
        case 0xFE965: return "E147";    // SoftBank
        case 0xFE966: return "E33A";    // SoftBank
        case 0xFE967: return "E33B";    // SoftBank
        case 0xFE968: return "E33C";    // SoftBank
        case 0xFE969: return "E33D";    // SoftBank
        case 0xFE96A: return "E33E";    // SoftBank
        case 0xFE96B: return "E33F";    // SoftBank
        case 0xFE96C: return "E341";    // SoftBank
        case 0xFE96D: return "E343";    // SoftBank
        case 0xFE96E: return "E344";    // SoftBank
        case 0xFE96F: return "E34C";    // SoftBank
        case 0xFE970: return "E34D";    // SoftBank
        case 0xFE971: return "E43F";    // SoftBank
        case 0xFE980: return "E043";    // SoftBank
        case 0xFE981: return "E045";    // SoftBank
        case 0xFE982: return "E044";    // SoftBank
        case 0xFE983: return "E047";    // SoftBank
        case 0xFE984: return "E338";    // SoftBank
        case 0xFE985: return "E30B";    // SoftBank
        case 0xFE986: return "E044";    // SoftBank
        case 0xFE987: return "E30C";    // SoftBank
        case 0xFE988: return "E044";    // SoftBank
        case 0xFEAF0: return "E236";    // SoftBank
        case 0xFEAF1: return "E238";    // SoftBank
        case 0xFEAF2: return "E237";    // SoftBank
        case 0xFEAF3: return "E239";    // SoftBank
        case 0xFEAF4: return "E236";    // SoftBank
        case 0xFEAF5: return "E238";    // SoftBank
        case 0xFEAF8: return "E232";    // SoftBank
        case 0xFEAF9: return "E233";    // SoftBank
        case 0xFEAFA: return "E234";    // SoftBank
        case 0xFEAFB: return "E235";    // SoftBank
        case 0xFEAFC: return "E23A";    // SoftBank
        case 0xFEAFD: return "E23B";    // SoftBank
        case 0xFEAFE: return "E23C";    // SoftBank
        case 0xFEAFF: return "E23D";    // SoftBank
        case 0xFEB04: return "E021";    // SoftBank
        case 0xFEB09: return "E020";    // SoftBank
        case 0xFEB0A: return "E336";    // SoftBank
        case 0xFEB0B: return "E337";    // SoftBank
        case 0xFEB0C: return "E022";    // SoftBank
        case 0xFEB0D: return "E327";    // SoftBank
        case 0xFEB0E: return "E023";    // SoftBank
        case 0xFEB0F: return "E327";    // SoftBank
        case 0xFEB10: return "E327";    // SoftBank
        case 0xFEB11: return "E328";    // SoftBank
        case 0xFEB12: return "E329";    // SoftBank
        case 0xFEB13: return "E32A";    // SoftBank
        case 0xFEB14: return "E32B";    // SoftBank
        case 0xFEB15: return "E32C";    // SoftBank
        case 0xFEB16: return "E32D";    // SoftBank
        case 0xFEB17: return "E437";    // SoftBank
        case 0xFEB18: return "E327";    // SoftBank
        case 0xFEB19: return "E204";    // SoftBank
        case 0xFEB1A: return "E20C";    // SoftBank
        case 0xFEB1B: return "E20E";    // SoftBank
        case 0xFEB1C: return "E20D";    // SoftBank
        case 0xFEB1D: return "E20F";    // SoftBank
        case 0xFEB1E: return "E30E";    // SoftBank
        case 0xFEB1F: return "E208";    // SoftBank
        case 0xFEB20: return "E20A";    // SoftBank
        case 0xFEB23: return "E252";    // SoftBank
        case 0xFEB24: return "E203";    // SoftBank
        case 0xFEB25: return "E207";    // SoftBank
        case 0xFEB26: return "E137";    // SoftBank
        case 0xFEB27: return "E24D";    // SoftBank
        case 0xFEB29: return "E24E";    // SoftBank
        case 0xFEB2A: return "E537";    // SoftBank
        case 0xFEB2B: return "E315";    // SoftBank
        case 0xFEB2D: return "E24F";    // SoftBank
        case 0xFEB2F: return "E22B";    // SoftBank
        case 0xFEB31: return "E22A";    // SoftBank
        case 0xFEB32: return "E12E";    // SoftBank
        case 0xFEB33: return "E138";    // SoftBank
        case 0xFEB34: return "E139";    // SoftBank
        case 0xFEB35: return "E13A";    // SoftBank
        case 0xFEB36: return "E212";    // SoftBank
        case 0xFEB37: return "E213";    // SoftBank
        case 0xFEB38: return "E214";    // SoftBank
        case 0xFEB39: return "E215";    // SoftBank
        case 0xFEB3A: return "E216";    // SoftBank
        case 0xFEB3B: return "E217";    // SoftBank
        case 0xFEB3C: return "E218";    // SoftBank
        case 0xFEB3D: return "E226";    // SoftBank
        case 0xFEB3E: return "E227";    // SoftBank
        case 0xFEB3F: return "E228";    // SoftBank
        case 0xFEB40: return "E22C";    // SoftBank
        case 0xFEB41: return "E22D";    // SoftBank
        case 0xFEB42: return "E24C";    // SoftBank
        case 0xFEB43: return "E30D";    // SoftBank
        case 0xFEB44: return "E332";    // SoftBank
        case 0xFEB45: return "E333";    // SoftBank
        case 0xFEB46: return "E333";    // SoftBank
        case 0xFEB53: return "E333";    // SoftBank
        case 0xFEB56: return "E10F";    // SoftBank
        case 0xFEB57: return "E334";    // SoftBank
        case 0xFEB58: return "E311";    // SoftBank
        case 0xFEB59: return "E13C";    // SoftBank
        case 0xFEB5B: return "E331";    // SoftBank
        case 0xFEB5C: return "E331";    // SoftBank
        case 0xFEB5D: return "E330";    // SoftBank
        case 0xFEB5E: return "E14C";    // SoftBank
        case 0xFEB5F: return "E407";    // SoftBank
        case 0xFEB60: return "E32E";    // SoftBank
        case 0xFEB61: return "E205";    // SoftBank
        case 0xFEB62: return "E206";    // SoftBank
        case 0xFEB63: return "E219";    // SoftBank
        case 0xFEB64: return "E21A";    // SoftBank
        case 0xFEB65: return "E219";    // SoftBank
        case 0xFEB66: return "E219";    // SoftBank
        case 0xFEB67: return "E21B";    // SoftBank
        case 0xFEB68: return "E32F";    // SoftBank
        case 0xFEB69: return "E335";    // SoftBank
        case 0xFEB6B: return "E21B";    // SoftBank
        case 0xFEB6C: return "E21A";    // SoftBank
        case 0xFEB6D: return "E21B";    // SoftBank
        case 0xFEB6E: return "E21A";    // SoftBank
        case 0xFEB6F: return "E21B";    // SoftBank
        case 0xFEB70: return "E21A";    // SoftBank
        case 0xFEB71: return "E21B";    // SoftBank
        case 0xFEB72: return "E21A";    // SoftBank
        case 0xFEB73: return "E21B";    // SoftBank
        case 0xFEB74: return "E21B";    // SoftBank
        case 0xFEB75: return "E21B";    // SoftBank
        case 0xFEB76: return "E21B";    // SoftBank
        case 0xFEB77: return "E32E";    // SoftBank
        case 0xFEB81: return "E229";    // SoftBank
        case 0xFEB82: return "E03F";    // SoftBank
        case 0xFEB85: return "E114";    // SoftBank
        case 0xFEB86: return "E144";    // SoftBank
        case 0xFEB87: return "E145";    // SoftBank
        case 0xFEB8A: return "E144";    // SoftBank
        case 0xFEB8D: return "E114";    // SoftBank
        case 0xFEB8E: return "E235";    // SoftBank
        case 0xFEB90: return "E144";    // SoftBank
        case 0xFEB92: return "E103";    // SoftBank
        case 0xFEB93: return "E010";    // SoftBank
        case 0xFEB94: return "E011";    // SoftBank
        case 0xFEB95: return "E012";    // SoftBank
        case 0xFEB96: return "E00D";    // SoftBank
        case 0xFEB97: return "E00E";    // SoftBank
        case 0xFEB98: return "E00F";    // SoftBank
        case 0xFEB99: return "E22E";    // SoftBank
        case 0xFEB9A: return "E22F";    // SoftBank
        case 0xFEB9B: return "E230";    // SoftBank
        case 0xFEB9C: return "E231";    // SoftBank
        case 0xFEB9D: return "E41E";    // SoftBank
        case 0xFEB9E: return "E41F";    // SoftBank
        case 0xFEB9F: return "E420";    // SoftBank
        case 0xFEBA0: return "E421";    // SoftBank
        case 0xFEBA1: return "E422";    // SoftBank
        case 0xFEE1C: return "E03D";    // SoftBank
        case 0xFEE70: return "E538";    // SoftBank
        case 0xFEE71: return "E539";    // SoftBank
        case 0xFEE72: return "E53A";    // SoftBank
        case 0xFEE73: return "E53B";    // SoftBank
        case 0xFEE74: return "E53C";    // SoftBank
        case 0xFEE75: return "E53D";    // SoftBank
        case 0xFEE76: return "E53E";    // SoftBank
        case 0xFEE77: return "E254";    // SoftBank
        case 0xFEE78: return "E255";    // SoftBank
        case 0xFEE79: return "E256";    // SoftBank
        case 0xFEE7A: return "E257";    // SoftBank
        case 0xFEE7B: return "E258";    // SoftBank
        case 0xFEE7C: return "E259";    // SoftBank
        case 0xFEE7D: return "E25A";    // SoftBank
        default: return null;
        }
    }

    private static String getEmojiForCodePointForEmoticon(int codePoint) {
        // Derived from http://code.google.com/p/emoji4unicode/source/browse/trunk/data/emoji4unicode.xml
        // XXX: This doesn't cover all the characters.  More emoticons are wanted.
        switch (codePoint) {
        case 0xFE000:
            return "sun";
        case 0xFE001:
            return "cloud";
        case 0xFE002:
            return "rain";
        case 0xFE003:
            return "snow";
        case 0xFE004:
            return "thunder";
        case 0xFE005:
            return "typhoon";
        case 0xFE006:
            return "mist";
        case 0xFE007:
            return "sprinkle";
        case 0xFE008:
            return "night";
        case 0xFE009:
            return "sun";
        case 0xFE00A:
            return "sun";
        case 0xFE00C:
            return "sun";
        case 0xFE010:
            return "night";
        case 0xFE011:
            return "newmoon";
        case 0xFE012:
            return "moon1";
        case 0xFE013:
            return "moon2";
        case 0xFE014:
            return "moon3";
        case 0xFE015:
            return "fullmoon";
        case 0xFE016:
            return "moon2";
        case 0xFE018:
            return "soon";
        case 0xFE019:
            return "on";
        case 0xFE01A:
            return "end";
        case 0xFE01B:
            return "sandclock";
        case 0xFE01C:
            return "sandclock";
        case 0xFE01D:
            return "watch";
        case 0xFE01E:
            return "clock";
        case 0xFE01F:
            return "clock";
        case 0xFE020:
            return "clock";
        case 0xFE021:
            return "clock";
        case 0xFE022:
            return "clock";
        case 0xFE023:
            return "clock";
        case 0xFE024:
            return "clock";
        case 0xFE025:
            return "clock";
        case 0xFE026:
            return "clock";
        case 0xFE027:
            return "clock";
        case 0xFE028:
            return "clock";
        case 0xFE029:
            return "clock";
        case 0xFE02A:
            return "clock";
        case 0xFE02B:
            return "aries";
        case 0xFE02C:
            return "taurus";
        case 0xFE02D:
            return "gemini";
        case 0xFE02E:
            return "cancer";
        case 0xFE02F:
            return "leo";
        case 0xFE030:
            return "virgo";
        case 0xFE031:
            return "libra";
        case 0xFE032:
            return "scorpius";
        case 0xFE033:
            return "sagittarius";
        case 0xFE034:
            return "capricornus";
        case 0xFE035:
            return "aquarius";
        case 0xFE036:
            return "pisces";
        case 0xFE038:
            return "wave";
        case 0xFE03B:
            return "night";
        case 0xFE03C:
            return "clover";
        case 0xFE03D:
            return "tulip";
        case 0xFE03E:
            return "bud";
        case 0xFE03F:
            return "maple";
        case 0xFE040:
            return "cherryblossom";
        case 0xFE042:
            return "maple";
        case 0xFE04E:
            return "clover";
        case 0xFE04F:
            return "cherry";
        case 0xFE050:
            return "banana";
        case 0xFE051:
            return "apple";
        case 0xFE05B:
            return "apple";
        case 0xFE190:
            return "eye";
        case 0xFE191:
            return "ear";
        case 0xFE193:
            return "kissmark";
        case 0xFE194:
            return "bleah";
        case 0xFE195:
            return "rouge";
        case 0xFE198:
            return "hairsalon";
        case 0xFE19A:
            return "shadow";
        case 0xFE19B:
            return "happy01";
        case 0xFE19C:
            return "happy01";
        case 0xFE19D:
            return "happy01";
        case 0xFE19E:
            return "happy01";
        case 0xFE1B7:
            return "dog";
        case 0xFE1B8:
            return "cat";
        case 0xFE1B9:
            return "snail";
        case 0xFE1BA:
            return "chick";
        case 0xFE1BB:
            return "chick";
        case 0xFE1BC:
            return "penguin";
        case 0xFE1BD:
            return "fish";
        case 0xFE1BE:
            return "horse";
        case 0xFE1BF:
            return "pig";
        case 0xFE1C8:
            return "chick";
        case 0xFE1C9:
            return "fish";
        case 0xFE1CF:
            return "aries";
        case 0xFE1D0:
            return "dog";
        case 0xFE1D8:
            return "dog";
        case 0xFE1D9:
            return "fish";
        case 0xFE1DB:
            return "foot";
        case 0xFE1DD:
            return "chick";
        case 0xFE1E0:
            return "pig";
        case 0xFE1E3:
            return "cancer";
        case 0xFE320:
            return "angry";
        case 0xFE321:
            return "sad";
        case 0xFE322:
            return "wobbly";
        case 0xFE323:
            return "despair";
        case 0xFE324:
            return "wobbly";
        case 0xFE325:
            return "coldsweats02";
        case 0xFE326:
            return "gawk";
        case 0xFE327:
            return "lovely";
        case 0xFE328:
            return "smile";
        case 0xFE329:
            return "bleah";
        case 0xFE32A:
            return "bleah";
        case 0xFE32B:
            return "delicious";
        case 0xFE32C:
            return "lovely";
        case 0xFE32D:
            return "lovely";
        case 0xFE32F:
            return "happy02";
        case 0xFE330:
            return "happy01";
        case 0xFE331:
            return "coldsweats01";
        case 0xFE332:
            return "happy02";
        case 0xFE333:
            return "smile";
        case 0xFE334:
            return "happy02";
        case 0xFE335:
            return "delicious";
        case 0xFE336:
            return "happy01";
        case 0xFE337:
            return "happy01";
        case 0xFE338:
            return "coldsweats01";
        case 0xFE339:
            return "weep";
        case 0xFE33A:
            return "crying";
        case 0xFE33B:
            return "shock";
        case 0xFE33C:
            return "bearing";
        case 0xFE33D:
            return "pout";
        case 0xFE33E:
            return "confident";
        case 0xFE33F:
            return "sad";
        case 0xFE340:
            return "think";
        case 0xFE341:
            return "shock";
        case 0xFE342:
            return "sleepy";
        case 0xFE343:
            return "catface";
        case 0xFE344:
            return "coldsweats02";
        case 0xFE345:
            return "coldsweats02";
        case 0xFE346:
            return "bearing";
        case 0xFE347:
            return "wink";
        case 0xFE348:
            return "happy01";
        case 0xFE349:
            return "smile";
        case 0xFE34A:
            return "happy02";
        case 0xFE34B:
            return "lovely";
        case 0xFE34C:
            return "lovely";
        case 0xFE34D:
            return "weep";
        case 0xFE34E:
            return "pout";
        case 0xFE34F:
            return "smile";
        case 0xFE350:
            return "sad";
        case 0xFE351:
            return "ng";
        case 0xFE352:
            return "ok";
        case 0xFE357:
            return "paper";
        case 0xFE359:
            return "sad";
        case 0xFE35A:
            return "angry";
        case 0xFE4B0:
            return "house";
        case 0xFE4B1:
            return "house";
        case 0xFE4B2:
            return "building";
        case 0xFE4B3:
            return "postoffice";
        case 0xFE4B4:
            return "hospital";
        case 0xFE4B5:
            return "bank";
        case 0xFE4B6:
            return "atm";
        case 0xFE4B7:
            return "hotel";
        case 0xFE4B9:
            return "24hours";
        case 0xFE4BA:
            return "school";
        case 0xFE4C1:
            return "ship";
        case 0xFE4C2:
            return "bottle";
        case 0xFE4C3:
            return "fuji";
        case 0xFE4C9:
            return "wrench";
        case 0xFE4CC:
            return "shoe";
        case 0xFE4CD:
            return "shoe";
        case 0xFE4CE:
            return "eyeglass";
        case 0xFE4CF:
            return "t-shirt";
        case 0xFE4D0:
            return "denim";
        case 0xFE4D1:
            return "crown";
        case 0xFE4D2:
            return "crown";
        case 0xFE4D6:
            return "boutique";
        case 0xFE4D7:
            return "boutique";
        case 0xFE4DB:
            return "t-shirt";
        case 0xFE4DC:
            return "moneybag";
        case 0xFE4DD:
            return "dollar";
        case 0xFE4E0:
            return "dollar";
        case 0xFE4E2:
            return "yen";
        case 0xFE4E3:
            return "dollar";
        case 0xFE4EF:
            return "camera";
        case 0xFE4F0:
            return "bag";
        case 0xFE4F1:
            return "pouch";
        case 0xFE4F2:
            return "bell";
        case 0xFE4F3:
            return "door";
        case 0xFE4F9:
            return "movie";
        case 0xFE4FB:
            return "flair";
        case 0xFE4FD:
            return "sign05";
        case 0xFE4FF:
            return "book";
        case 0xFE500:
            return "book";
        case 0xFE501:
            return "book";
        case 0xFE502:
            return "book";
        case 0xFE503:
            return "book";
        case 0xFE505:
            return "spa";
        case 0xFE506:
            return "toilet";
        case 0xFE507:
            return "toilet";
        case 0xFE508:
            return "toilet";
        case 0xFE50F:
            return "ribbon";
        case 0xFE510:
            return "present";
        case 0xFE511:
            return "birthday";
        case 0xFE512:
            return "xmas";
        case 0xFE522:
            return "pocketbell";
        case 0xFE523:
            return "telephone";
        case 0xFE524:
            return "telephone";
        case 0xFE525:
            return "mobilephone";
        case 0xFE526:
            return "phoneto";
        case 0xFE527:
            return "memo";
        case 0xFE528:
            return "faxto";
        case 0xFE529:
            return "mail";
        case 0xFE52A:
            return "mailto";
        case 0xFE52B:
            return "mailto";
        case 0xFE52C:
            return "postoffice";
        case 0xFE52D:
            return "postoffice";
        case 0xFE52E:
            return "postoffice";
        case 0xFE535:
            return "present";
        case 0xFE536:
            return "pen";
        case 0xFE537:
            return "chair";
        case 0xFE538:
            return "pc";
        case 0xFE539:
            return "pencil";
        case 0xFE53A:
            return "clip";
        case 0xFE53B:
            return "bag";
        case 0xFE53E:
            return "hairsalon";
        case 0xFE540:
            return "memo";
        case 0xFE541:
            return "memo";
        case 0xFE545:
            return "book";
        case 0xFE546:
            return "book";
        case 0xFE547:
            return "book";
        case 0xFE548:
            return "memo";
        case 0xFE54D:
            return "book";
        case 0xFE54F:
            return "book";
        case 0xFE552:
            return "memo";
        case 0xFE553:
            return "foot";
        case 0xFE7D0:
            return "sports";
        case 0xFE7D1:
            return "baseball";
        case 0xFE7D2:
            return "golf";
        case 0xFE7D3:
            return "tennis";
        case 0xFE7D4:
            return "soccer";
        case 0xFE7D5:
            return "ski";
        case 0xFE7D6:
            return "basketball";
        case 0xFE7D7:
            return "motorsports";
        case 0xFE7D8:
            return "snowboard";
        case 0xFE7D9:
            return "run";
        case 0xFE7DA:
            return "snowboard";
        case 0xFE7DC:
            return "horse";
        case 0xFE7DF:
            return "train";
        case 0xFE7E0:
            return "subway";
        case 0xFE7E1:
            return "subway";
        case 0xFE7E2:
            return "bullettrain";
        case 0xFE7E3:
            return "bullettrain";
        case 0xFE7E4:
            return "car";
        case 0xFE7E5:
            return "rvcar";
        case 0xFE7E6:
            return "bus";
        case 0xFE7E8:
            return "ship";
        case 0xFE7E9:
            return "airplane";
        case 0xFE7EA:
            return "yacht";
        case 0xFE7EB:
            return "bicycle";
        case 0xFE7EE:
            return "yacht";
        case 0xFE7EF:
            return "car";
        case 0xFE7F0:
            return "run";
        case 0xFE7F5:
            return "gasstation";
        case 0xFE7F6:
            return "parking";
        case 0xFE7F7:
            return "signaler";
        case 0xFE7FA:
            return "spa";
        case 0xFE7FC:
            return "carouselpony";
        case 0xFE7FF:
            return "fish";
        case 0xFE800:
            return "karaoke";
        case 0xFE801:
            return "movie";
        case 0xFE802:
            return "movie";
        case 0xFE803:
            return "music";
        case 0xFE804:
            return "art";
        case 0xFE805:
            return "drama";
        case 0xFE806:
            return "event";
        case 0xFE807:
            return "ticket";
        case 0xFE808:
            return "slate";
        case 0xFE809:
            return "drama";
        case 0xFE80A:
            return "game";
        case 0xFE813:
            return "note";
        case 0xFE814:
            return "notes";
        case 0xFE81A:
            return "notes";
        case 0xFE81C:
            return "tv";
        case 0xFE81D:
            return "cd";
        case 0xFE81E:
            return "cd";
        case 0xFE823:
            return "kissmark";
        case 0xFE824:
            return "loveletter";
        case 0xFE825:
            return "ring";
        case 0xFE826:
            return "ring";
        case 0xFE827:
            return "kissmark";
        case 0xFE829:
            return "heart02";
        case 0xFE82B:
            return "freedial";
        case 0xFE82C:
            return "sharp";
        case 0xFE82D:
            return "mobaq";
        case 0xFE82E:
            return "one";
        case 0xFE82F:
            return "two";
        case 0xFE830:
            return "three";
        case 0xFE831:
            return "four";
        case 0xFE832:
            return "five";
        case 0xFE833:
            return "six";
        case 0xFE834:
            return "seven";
        case 0xFE835:
            return "eight";
        case 0xFE836:
            return "nine";
        case 0xFE837:
            return "zero";
        case 0xFE960:
            return "fastfood";
        case 0xFE961:
            return "riceball";
        case 0xFE962:
            return "cake";
        case 0xFE963:
            return "noodle";
        case 0xFE964:
            return "bread";
        case 0xFE96A:
            return "noodle";
        case 0xFE973:
            return "typhoon";
        case 0xFE980:
            return "restaurant";
        case 0xFE981:
            return "cafe";
        case 0xFE982:
            return "bar";
        case 0xFE983:
            return "beer";
        case 0xFE984:
            return "japanesetea";
        case 0xFE985:
            return "bottle";
        case 0xFE986:
            return "wine";
        case 0xFE987:
            return "beer";
        case 0xFE988:
            return "bar";
        case 0xFEAF0:
            return "upwardright";
        case 0xFEAF1:
            return "downwardright";
        case 0xFEAF2:
            return "upwardleft";
        case 0xFEAF3:
            return "downwardleft";
        case 0xFEAF4:
            return "up";
        case 0xFEAF5:
            return "down";
        case 0xFEAF6:
            return "leftright";
        case 0xFEAF7:
            return "updown";
        case 0xFEB04:
            return "sign01";
        case 0xFEB05:
            return "sign02";
        case 0xFEB06:
            return "sign03";
        case 0xFEB07:
            return "sign04";
        case 0xFEB08:
            return "sign05";
        case 0xFEB0B:
            return "sign01";
        case 0xFEB0C:
            return "heart01";
        case 0xFEB0D:
            return "heart02";
        case 0xFEB0E:
            return "heart03";
        case 0xFEB0F:
            return "heart04";
        case 0xFEB10:
            return "heart01";
        case 0xFEB11:
            return "heart02";
        case 0xFEB12:
            return "heart01";
        case 0xFEB13:
            return "heart01";
        case 0xFEB14:
            return "heart01";
        case 0xFEB15:
            return "heart01";
        case 0xFEB16:
            return "heart01";
        case 0xFEB17:
            return "heart01";
        case 0xFEB18:
            return "heart02";
        case 0xFEB19:
            return "cute";
        case 0xFEB1A:
            return "heart";
        case 0xFEB1B:
            return "spade";
        case 0xFEB1C:
            return "diamond";
        case 0xFEB1D:
            return "club";
        case 0xFEB1E:
            return "smoking";
        case 0xFEB1F:
            return "nosmoking";
        case 0xFEB20:
            return "wheelchair";
        case 0xFEB21:
            return "free";
        case 0xFEB22:
            return "flag";
        case 0xFEB23:
            return "danger";
        case 0xFEB26:
            return "ng";
        case 0xFEB27:
            return "ok";
        case 0xFEB28:
            return "ng";
        case 0xFEB29:
            return "copyright";
        case 0xFEB2A:
            return "tm";
        case 0xFEB2B:
            return "secret";
        case 0xFEB2C:
            return "recycle";
        case 0xFEB2D:
            return "r-mark";
        case 0xFEB2E:
            return "ban";
        case 0xFEB2F:
            return "empty";
        case 0xFEB30:
            return "pass";
        case 0xFEB31:
            return "full";
        case 0xFEB36:
            return "new";
        case 0xFEB44:
            return "fullmoon";
        case 0xFEB48:
            return "ban";
        case 0xFEB55:
            return "cute";
        case 0xFEB56:
            return "flair";
        case 0xFEB57:
            return "annoy";
        case 0xFEB58:
            return "bomb";
        case 0xFEB59:
            return "sleepy";
        case 0xFEB5A:
            return "impact";
        case 0xFEB5B:
            return "sweat01";
        case 0xFEB5C:
            return "sweat02";
        case 0xFEB5D:
            return "dash";
        case 0xFEB5F:
            return "sad";
        case 0xFEB60:
            return "shine";
        case 0xFEB61:
            return "cute";
        case 0xFEB62:
            return "cute";
        case 0xFEB63:
            return "newmoon";
        case 0xFEB64:
            return "newmoon";
        case 0xFEB65:
            return "newmoon";
        case 0xFEB66:
            return "newmoon";
        case 0xFEB67:
            return "newmoon";
        case 0xFEB77:
            return "shine";
        case 0xFEB81:
            return "id";
        case 0xFEB82:
            return "key";
        case 0xFEB83:
            return "enter";
        case 0xFEB84:
            return "clear";
        case 0xFEB85:
            return "search";
        case 0xFEB86:
            return "key";
        case 0xFEB87:
            return "key";
        case 0xFEB8A:
            return "key";
        case 0xFEB8D:
            return "search";
        case 0xFEB90:
            return "key";
        case 0xFEB91:
            return "recycle";
        case 0xFEB92:
            return "mail";
        case 0xFEB93:
            return "rock";
        case 0xFEB94:
            return "scissors";
        case 0xFEB95:
            return "paper";
        case 0xFEB96:
            return "punch";
        case 0xFEB97:
            return "good";
        case 0xFEB9D:
            return "paper";
        case 0xFEB9F:
            return "ok";
        case 0xFEBA0:
            return "down";
        case 0xFEBA1:
            return "paper";
        case 0xFEE10:
            return "info01";
        case 0xFEE11:
            return "info02";
        case 0xFEE12:
            return "by-d";
        case 0xFEE13:
            return "d-point";
        case 0xFEE14:
            return "appli01";
        case 0xFEE15:
            return "appli02";
        case 0xFEE1C:
            return "movie";
        default:
            return null;
        }
    }

    private static String htmlifyMessageHeader() {
        return "<pre class=\"" + K9MAIL_CSS_CLASS + "\">";
    }

    private static String htmlifyMessageFooter() {
        return "</pre>";
    }

    /**
     * Dynamically generate a CSS style for {@code <pre>} elements.
     *
     *  <p>
     *  The style incorporates the user's current preference
     *  setting for the font family used for plain text messages.
     *  </p>
     *
     * @return
     *      A {@code <style>} element that can be dynamically included in the HTML
     *      {@code <head>} element when messages are displayed.
     */
    public static String cssStylePre() {
        final String font = K9.messageViewFixedWidthFont()
                ? "monospace"
                : "sans-serif";
        return "<style type=\"text/css\"> pre." + K9MAIL_CSS_CLASS +
                " {white-space: pre-wrap; word-wrap:break-word; " +
                "font-family: " + font + "; margin-top: 0px}</style>";
    }

    /**
     * Convert a plain text string into an HTML fragment.
     * @param text Plain text.
     * @return HTML fragment.
     */
    public static String textToHtmlFragment(final String text) {
        // Escape the entities and add newlines.
        String htmlified = TextUtils.htmlEncode(text);

        // Linkify the message.
        StringBuffer linkified = new StringBuffer(htmlified.length() + TEXT_TO_HTML_EXTRA_BUFFER_LENGTH);
        linkifyText(htmlified, linkified);

        // Add newlines and unescaping.
        //
        // For some reason, TextUtils.htmlEncode escapes ' into &apos;, which is technically part of the XHTML 1.0
        // standard, but Gmail doesn't recognize it as an HTML entity. We unescape that here.
        return linkified.toString().replaceAll("\r?\n", "<br>\r\n").replace("&apos;", "&#39;");
    }

    /**
     * Convert HTML to a {@link Spanned} that can be used in a {@link android.widget.TextView}.
     *
     * @param html
     *         The HTML fragment to be converted.
     *
     * @return A {@link Spanned} containing the text in {@code html} formatted using spans.
     */
    public static Spanned htmlToSpanned(String html) {
        return Html.fromHtml(html, null, new ListTagHandler());
    }

    /**
     * {@link TagHandler} that supports unordered lists.
     *
     * @see HtmlConverter#htmlToSpanned(String)
     */
    public static class ListTagHandler implements TagHandler {
        @Override
        public void handleTag(boolean opening, String tag, Editable output, XMLReader xmlReader) {
            if (tag.equals("ul")) {
                if (opening) {
                    char lastChar = 0;
                    if (output.length() > 0) {
                        lastChar = output.charAt(output.length() - 1);
                    }
                    if (lastChar != '\n') {
                        output.append("\r\n");
                    }
                } else {
                    output.append("\r\n");
                }
            }

            if (tag.equals("li")) {
                if (opening) {
                    output.append("\t•  ");
                } else {
                    output.append("\r\n");
                }
            }
        }
    }
}
