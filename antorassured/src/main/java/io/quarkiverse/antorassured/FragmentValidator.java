package io.quarkiverse.antorassured;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector.SelectorParseException;

/**
 * Validates fragments, such as {@code #chapter-one} which possibly occur at the end of URIs.
 *
 * @since 2.0.0
 */
public interface FragmentValidator {

    /**
     * Validate the fragment of the given {@link Link}. If the given {@link Link} has no fragment, then always returns a
     * valid {@link ValidationResult}.
     *
     * @param link the {@link Link} to validate
     * @param response a possibly cached response retrieved from the given {@link Link}
     * @return a {@link ValidationResult}
     *
     * @since 2.0.0
     */
    ValidationResult validate(Link link, Response response);

    /**
     * @return a {@link FragmentValidator} that always returns a valid {@link ValidationResult}.
     *
     * @since 2.0.0
     */
    static FragmentValidator alwaysValid() {
        return (link, response) -> ValidationResult.valid(link, response.statusCode());
    }

    /**
     * @return the default {@link FragmentValidator}
     *
     * @since 2.0.0
     */
    static FragmentValidator defaultFragmentValidator() {
        return DefaultFragmentValidator.INSTANCE;
    }

    /**
     * @return a {@link FragmentValidator} able to validate line number fragments such as {@code #L5} or {@code L5-L8}
     *         against raw source files as retrieved using {@code https://api.github.com/repos/OWNER/REPO/contents/PATH}
     *         with {@code Accept: application/vnd.github.raw+json}
     *
     * @since 2.0.0
     */
    static FragmentValidator gitHubRawBlobFragmentValidator() {
        return new GitHubRawContentFragmentValidator();
    }

    /**
     * A representation of a raw text document able to validate whether specific line numbers exist.
     *
     * @since 2.0.0
     */
    public record RawTextDocument(int lastLineNumber) {
        /**
         * @param lineNumber the line number to validate. The first line number is {@code 1}
         * @return {@code true} is this {@link RawTextDocument} has line with the given {@code lineNumber}
         *
         * @since 2.0.0
         */
        public boolean hasLine(int lineNumber) {
            return lineNumber > 0 && lineNumber <= lastLineNumber;
        }

        /**
         * Validates a line span.
         *
         * @param startLineNumber the first line number of the line span to validate. The first line number is {@code 1}
         * @param endLineNumber the last line number of the line span to validate.
         * @return {@code true} is this {@link RawTextDocument} contains the line span defined by
         *         {@code startLineNumber} and {@code endLineNumber}
         *
         * @since 2.0.0
         */
        public boolean hasLines(int startLineNumber, int endLineNumber) {
            return startLineNumber > 0 && startLineNumber <= endLineNumber && endLineNumber <= lastLineNumber;
        }
    }

    static class GitHubRawContentFragmentValidator implements FragmentValidator {
        private static final Pattern LINE_PATTERN = Pattern.compile("\\#L([0-9]+)");
        private static final Pattern LINES_PATTERN = Pattern.compile("\\#L([0-9]+)-L([0-9]+)");

        @Override
        public ValidationResult validate(Link link, Response response) {
            final String fragment = link.fragment();
            /* No fragment */
            if (fragment == null) {
                return ValidationResult.valid(link, response.statusCode());
            }
            final Matcher m = LINE_PATTERN.matcher(fragment);
            final boolean isLineFragment = m.matches();
            final Matcher mm = LINES_PATTERN.matcher(fragment);
            final boolean isLinesFragment = mm.matches();

            if (isLineFragment || isLinesFragment) {
                final String bodyString = response.bodyAsString();
                final RawTextDocument text = response.bodyAs(RawTextDocument.class, resp -> {
                    int lastLineNumber = 1;
                    for (int i = 0; i < bodyString.length(); i++) {
                        if (bodyString.charAt(i) == '\n') {
                            lastLineNumber++;
                        }
                    }
                    return new RawTextDocument(lastLineNumber);
                });
                if (isLineFragment) {
                    if (text.hasLine(Integer.parseInt(m.group(1)))) {
                        return ValidationResult.valid(link, response.statusCode());
                    } else {
                        return ValidationResult.invalid(link, response.statusCode(), "Fragment " + fragment + " not found", -1);
                    }
                }
                if (isLinesFragment) {
                    if (text.hasLines(Integer.parseInt(mm.group(1)), Integer.parseInt(mm.group(2)))) {
                        return ValidationResult.valid(link, response.statusCode());
                    } else {
                        return ValidationResult.invalid(link, response.statusCode(), "Fragment " + fragment + " not found", -1);
                    }
                }
            }
            return ValidationResult.invalid(link, response.statusCode(), "Fragment " + fragment + " not supported", -1);
        }

    }

    static class DefaultFragmentValidator implements FragmentValidator {
        private static final Logger log = Logger.getLogger(AntorAssured.class);
        private static final FragmentValidator INSTANCE = new DefaultFragmentValidator();
        private static final Pattern JAVADOC_FRAGMENT_CHARS = Pattern.compile("[\\(\\)\\,\\.]");

        @Override
        public ValidationResult validate(Link link, Response response) {
            final String fragment = link.fragment();
            /* No fragment */
            if (fragment == null) {
                return ValidationResult.valid(link, response.statusCode());
            }

            /* Find the fragment */
            final Document doc = response.bodyAsHtmlDocument();

            if (JAVADOC_FRAGMENT_CHARS.matcher(fragment).find()) {
                /* Those chars are illegal in CSS selectors, so Jsoup will fail at parsing the selector */
                final String id = fragment.substring(1);
                if (doc.getElementById(id) != null) {
                    return ValidationResult.valid(link, response.statusCode());
                } else {
                    return ValidationResult.invalid(link, response.statusCode(), "Could not find " + fragment, -1);
                }
            }

            try {
                Elements foundElems = doc.select(fragment);
                if (foundElems.isEmpty()) {
                    foundElems = doc.select("a[name=\"" + fragment.substring(1) + "\"]");
                }
                if (foundElems.isEmpty()) {
                    final String id = fragment.substring(1);
                    foundElems = doc.select("#user-content-" + id);
                }
                if (!foundElems.isEmpty()) {
                    return ValidationResult.valid(link, response.statusCode());
                } else {
                    return ValidationResult.invalid(link, response.statusCode(), "Could not find " + fragment, -1);
                }
            } catch (SelectorParseException e) {
                log.error("Bad fragment: " + fragment + " in URI " + link.originalUri(), e);
                throw e;
            }
        };
    }
}
