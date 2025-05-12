package io.quarkiverse.antorassured;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A link occurring in a HTML page.
 *
 * @since 1.0.0
 */
public class Link implements Comparable<Link> {
    private final Link mappedFrom;
    private final String originalUri;
    private final String resolvedUri;
    private final String resolvedFragmentlessUri;
    private final String fragment;
    private final Set<Path> occurrences;

    /**
     * @param absoluteUri
     * @return a new {@link Link} with both {@link #originalUri} and {@link #resolvedUri} set to the given {@code absoluteUri}
     *         and with empty {@link #occurrences}
     *
     * @since 1.0.0
     */
    public static Link ofResolved(String absoluteUri) {
        return new Link(null, absoluteUri, absoluteUri, Collections.emptySet());
    }

    Link(String originalUri, String resolvedUri, Set<Path> occurrences) {
        this(null, originalUri, resolvedUri, occurrences);
    }

    Link(Link mappedFrom, String originalUri, String resolvedUri, Set<Path> occurrences) {
        super();
        this.mappedFrom = mappedFrom;
        this.originalUri = originalUri;
        this.resolvedUri = resolvedUri;
        this.occurrences = occurrences;
        int hashPos = resolvedUri.indexOf('#');
        if (hashPos >= 0
                && hashPos < resolvedUri.length() - 1 // a single hash mark does not count for a fragment
        ) {

            fragment = resolvedUri.substring(hashPos);
            resolvedFragmentlessUri = resolvedUri.substring(0, hashPos);
        } else {
            fragment = null;
            resolvedFragmentlessUri = resolvedUri;
        }

    }

    /** Operates only on {@link #originalUri} and {@link #resolvedUri} */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Link other = (Link) obj;
        return Objects.equals(originalUri, other.originalUri)
                && Objects.equals(resolvedUri, other.resolvedUri);
    }

    /**
     * @return the URI as it originally occurred in an HTML page; esp. it can be a relative URI, such as {@code ../another.html}
     *
     * @since 1.0.0
     */
    public String originalUri() {
        return originalUri;
    }

    /**
     * @return {@link #originalUri} made absolute
     *
     * @since 1.0.0
     */
    public String resolvedUri() {
        return resolvedUri;
    }

    /**
     * @return {@link #resolvedUri} without fragment if it has one or otherwise {@link #resolvedUri} unchanged
     *
     * @since 2.0.0
     */
    public String resolvedFragmentlessUri() {
        return resolvedFragmentlessUri;
    }

    /**
     * @return a fragment (including the initial {@code #} character) if available in the {@link #resolvedUri} or otherwise
     *         {@code null}
     *
     * @since 2.0.0
     */
    public String fragment() {
        return fragment;
    }

    /**
     * @return a {@link Set} of HTML documents in which this Link occurs
     *
     * @since 1.0.0
     */
    public Set<Path> occurrences() {
        return occurrences;
    }

    /**
     * @param newOccurrences
     * @return a new {@link Link} with its {@link #occurrences} set to an unmodifiable copy of the given {@code newOccurrences}
     *
     * @since 1.0.0
     */
    public Link withOccurrences(Set<Path> newOccurrences) {
        return new Link(mappedFrom, originalUri, resolvedUri, Collections.unmodifiableSet(new TreeSet<Path>(newOccurrences)));
    }

    /**
     * @param resolvedUri
     * @return a new {@link Link} with its {@link #resolvedUri} set to the given {@code resolvedUri} and {@link #mappedFrom} set
     *         to this {@link Link}
     *
     * @since 2.0.0
     */
    public Link mapToUri(String resolvedUri) {
        return new Link(this, originalUri, resolvedUri, occurrences);
    }

    /**
     * @return {@code true} if {@link #resolvedUri} contains any of {@code //localhost}, {@code //127.0.0.1} or {@code //[::1]}
     *         as a substring
     *
     * @since 1.0.0
     */
    public boolean isLocalhost() {
        return resolvedUri.contains("//localhost")
                || resolvedUri.contains("//127.0.0.1")
                || resolvedUri.contains("//[::1]");
    }

    /** Includes only {@link #originalUri} and {@link #resolvedUri} */
    @Override
    public int hashCode() {
        return Objects.hash(originalUri, resolvedUri);
    }

    @Override
    public String toString() {
        if (mappedFrom != null) {
            return resolvedUri + " mapped from " + mappedFrom.toString();
        }
        return (originalUri.equals(resolvedUri) ? originalUri : (originalUri + " -> " + resolvedUri)) + " on [" + occurrences
                + "]";
    }

    /**
     * Appends {@link #originalUri} and {@link #resolvedUri} delimited by {@code " -> "} to the given {@code stringBuilder}
     * if the two differ; otherwise appends only {@link #originalUri}.
     *
     * @param stringBuilder where to append the short representation of this {@link Link}
     * @return the given {@code stringBuilder}
     *
     * @since 1.0.0
     */
    public StringBuilder toShortString(StringBuilder stringBuilder) {
        stringBuilder.append(originalUri);
        if (!originalUri.equals(resolvedUri)) {
            stringBuilder.append(" -> ").append(resolvedUri);
        }
        return stringBuilder;
    }

    @Override
    public int compareTo(Link other) {
        final int resolvedCmp = this.resolvedUri.compareTo(other.resolvedUri);
        if (resolvedCmp != 0) {
            return resolvedCmp;
        } else {
            return this.originalUri.compareTo(other.originalUri);
        }
    }

}
