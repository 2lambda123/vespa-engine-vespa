package ai.vespa.hosted.plugin;

import ai.vespa.hosted.api.ControllerHttpClient;
import ai.vespa.hosted.api.Properties;
import com.yahoo.config.provision.ApplicationId;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Base class for hosted Vespa plugin mojos.
 *
 * @author jonmv
 */
public abstract class AbstractVespaMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    @Parameter(property = "endpoint", defaultValue = "https://api.vespa-external.aws.oath.cloud:4443")
    protected String endpoint;

    @Parameter(property = "tenant")
    protected String tenant;

    @Parameter(property = "application")
    protected String application;

    @Parameter(property = "instance")
    protected String instance;

    @Parameter(property = "privateKey")
    protected String privateKey;

    @Parameter(property = "privateKeyFile")
    protected String privateKeyFile;

    @Parameter(property = "certificateFile")
    protected String certificateFile;

    // Fields set up as part of setup().
    protected ApplicationId id;
    protected ControllerHttpClient controller;

    @Override
    public final void execute() throws MojoExecutionException, MojoFailureException {
        try {
            setup();
            doExecute();
        }
        catch (MojoFailureException | MojoExecutionException e) {
            throw e;
        }
        catch (Exception e) {
            throw new MojoExecutionException("Execution failed for application '" + id + "':", e);
        }
    }

    /** Override this in subclasses, instead of {@link #execute()}. */
    protected abstract void doExecute() throws Exception;

    protected void setup() {
        tenant = firstNonBlank(tenant, project.getProperties().getProperty("tenant"));
        application = firstNonBlank(application, project.getProperties().getProperty("application"));
        instance = firstNonBlank(instance, project.getProperties().getProperty("instance", Properties.user()));
        id = ApplicationId.from(tenant, application, instance);

        if (privateKey != null) {
            controller = ControllerHttpClient.withSignatureKey(URI.create(endpoint), privateKey, id);
        } else if (privateKeyFile != null) {
            controller = certificateFile == null
                    ? ControllerHttpClient.withSignatureKey(URI.create(endpoint), Paths.get(privateKeyFile), id)
                    : ControllerHttpClient.withKeyAndCertificate(URI.create(endpoint), Paths.get(privateKeyFile), Paths.get(certificateFile));
        } else {
            throw new IllegalArgumentException("One of the properties 'privateKey' or 'privateKeyFile' is required.");
        }
    }

    protected String projectPathOf(String first, String... rest) {
        return project.getBasedir().toPath().resolve(Path.of(first, rest)).toString();
    }

    /** Returns the first of the given strings which is non-null and non-blank, or throws IllegalArgumentException. */
    protected static String firstNonBlank(String... values) {
        for (String value : values)
            if (value != null && ! value.isBlank())
                return value;

        throw new IllegalArgumentException("No valid value given");
    }

}
