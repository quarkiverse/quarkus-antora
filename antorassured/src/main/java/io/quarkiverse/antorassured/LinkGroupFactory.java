package io.quarkiverse.antorassured;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A factory for creating and configuring custom {@link LinkGroup}s.
 *
 * @since 2.0.0
 */
public interface LinkGroupFactory {
    /**
     * @param linkStream the {@link LinkStream} to use as a parent of the new {@link LinkGroup}
     * @return a new {@link LinkGroup} that can be further customized
     *
     * @since 2.0.0
     */
    LinkGroup createLinkGroup(LinkStream linkStream);

    /**
     * @param gitHubToken a GitHub token to access <a href=
     *        "https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28">https://api.github.com/repos/OWNER/REPO/contents/PATH</a>
     * @return a new {@link LinkGroup} that can be further customized
     *
     * @since 2.0.0
     */
    static LinkGroupFactory gitHubRawBlobLinks(String gitHubToken) {
        Objects.requireNonNull(gitHubToken,
                "Provide a GitHub token to access https://api.github.com/repos/OWNER/REPO/contents/PATH");
        return linkStream -> linkStream
                .group("https://github.com/[^/]+/[^/]+/(:?blob|tree)/.*")
                .bearerToken(gitHubToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Accept", "application/vnd.github.raw+json")
                .linkMapper(link -> {
                    final String result = Pattern
                            .compile(
                                    "https://github.com/([^/]+)/([^/]+)/(?:blob|tree)/([^/]+)/([^\\#]*)(\\#.*)?")
                            .matcher(link.resolvedUri())
                            .replaceAll("https://api.github.com/repos/$1/$2/contents/$4?ref=$3$5");
                    AntorAssured.log.debugf("Mapped:\n    %s -> \n    %s", link.resolvedUri(), result);
                    return link.mapToUri(result);
                })
                .fragmentValidator(FragmentValidator.gitHubRawBlobFragmentValidator());
    }

    /**
     * @param gitHubToken a GitHub token to access <a href=
     *        "https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28">https://api.github.com/repos/OWNER/REPO/contents/PATH</a>
     * @return a new {@link LinkGroup} that can be further customized
     *
     * @since 2.0.0
     */
    static LinkGroupFactory gitHubHtmlBlobLinks(String gitHubToken) {
        Objects.requireNonNull(gitHubToken,
                "Provide a GitHub token to access https://api.github.com/repos/OWNER/REPO/contents/PATH");
        return linkStream -> linkStream
                .group("https://github.com/[^/]+/[^/]+/(:?blob|tree)/.*")
                .bearerToken(gitHubToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Accept", "application/vnd.github.html+json")
                .linkMapper(link -> {
                    final String result = Pattern
                            .compile(
                                    "https://github.com/([^/]+)/([^/]+)/(?:blob|tree)/([^/]+)/([^\\#]*)(\\#.*)?")
                            .matcher(link.resolvedUri())
                            .replaceAll("https://api.github.com/repos/$1/$2/contents/$4?ref=$3$5");
                    AntorAssured.log.debugf("Mapped:\n    %s -> \n    %s", link.resolvedUri(), result);
                    return link.mapToUri(result);
                });
    }
}