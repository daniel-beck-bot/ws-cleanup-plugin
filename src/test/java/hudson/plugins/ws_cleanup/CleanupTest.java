/*
 * The MIT License
 *
 * Copyright (c) 2014 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ws_cleanup;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;
import static org.hamcrest.Matchers.*;

import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixRun;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.*;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.BuildWrapper;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.tasks.Shell;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;

import hudson.util.DescribableList;
import hudson.util.VersionNumber;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

public class CleanupTest {

    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public TemporaryFolder ws = new TemporaryFolder();

    // "IllegalArgumentException: Illegal group reference" observed when filename contained '$';
    @Test
    public void doNotTreatFilenameAsRegexReplaceWhenUsingCustomCommand() throws Exception {
        final String filename = "\\s! Dozen for $5 only!";

        FreeStyleProject p = j.jenkins.createProject(FreeStyleProject.class, "sut");
        populateWorkspace(p, filename);

        p.getBuildWrappersList().add(new PreBuildCleanup(Collections.<Pattern>emptyList(), false, null, "rm %s"));
        j.buildAndAssertSuccess(p);
    }

    @Test @Issue("JENKINS-20056")
    public void wipeOutWholeWorkspaceBeforeBuild() throws Exception {
        FreeStyleProject p = j.jenkins.createProject(FreeStyleProject.class, "sut");
        populateWorkspace(p, "content.txt");

        p.getBuildWrappersList().add(new PreBuildCleanup(Collections.<Pattern>emptyList(), false, null, null));
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        assertWorkspaceCleanedUp(b);
    }

    @Test @Issue("JENKINS-20056")
    public void wipeOutWholeWorkspaceAfterBuild() throws Exception {
        FreeStyleProject p = j.jenkins.createProject(FreeStyleProject.class, "sut");
        p.getBuildersList().add(new Shell("touch content.txt"));

        p.getPublishersList().add(wipeoutPublisher());
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        assertWorkspaceCleanedUp(b);
    }

    @Test @Issue("JENKINS-20056")
    public void wipeOutWholeWorkspaceAfterBuildMatrix() throws Exception {
        MatrixProject p = j.jenkins.createProject(MatrixProject.class, "sut");
        p.setAxes(new AxisList(new TextAxis("name", "a b")));
        p.getBuildWrappersList().add(new MatrixWsPopulator());
        p.getPublishersList().add(wipeoutPublisher());
        MatrixBuild b = j.buildAndAssertSuccess(p);

        assertWorkspaceCleanedUp(b);

        assertWorkspaceCleanedUp(p.getItem("name=a").getLastBuild());
        assertWorkspaceCleanedUp(p.getItem("name=b").getLastBuild());
    }

    @Test @Issue("JENKINS-20056")
    public void workspaceShouldNotBeManipulated() throws Exception {
        final int ITERATIONS = 50;

        FreeStyleProject p = j.jenkins.createProject(FreeStyleProject.class, "sut");
        p.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("RAND", "")));
        p.setConcurrentBuild(true);
        p.getBuildWrappersList().add(new PreBuildCleanup(Collections.<Pattern>emptyList(), false, null, null));
        p.getPublishersList().add(wipeoutPublisher());
        p.getBuildersList().add(new Shell(
                "echo =$BUILD_NUMBER= > marker;" +
                // Something hopefully expensive to delete
                "mkdir -p a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q/r/s/t/u/v/w/x/y/z/a/b/c/d/e/f/g/h/j/k/l/m/n/o/p/q/r/s//u/v/w/x/y/z/" +
                "sleep $(($BUILD_NUMBER%5));" +
                "grep =$BUILD_NUMBER= marker"
        ));

        final List<Future<FreeStyleBuild>> futureBuilds = new ArrayList<Future<FreeStyleBuild>>(ITERATIONS);

        for (int i = 0; i < ITERATIONS; i++) {
            futureBuilds.add(p.scheduleBuild2(0, (Cause) null, new ParametersAction(
                    new StringParameterValue("RAND", Integer.toString(i))
            )));
        }

        for (Future<FreeStyleBuild> fb: futureBuilds) {
            j.assertBuildStatusSuccess(fb.get());
        }
    }

    @Test
    public void deleteWorkspaceWithNonAsciiCharacters() throws Exception {
        FreeStyleProject p = j.jenkins.createProject(FreeStyleProject.class, "sut");
        p.getBuildersList().add(new Shell("touch a¶‱ﻷ.txt"));

        p.getPublishersList().add(wipeoutPublisher());

        FreeStyleBuild build = j.buildAndAssertSuccess(p);

        assertWorkspaceCleanedUp(build);
    }

    @Test @Issue("JENKINS-26250")
    public void doNotFailToWipeoutWhenRenameFails() throws Exception {
        assumeTrue(!Functions.isWindows()); // chmod does not work here

        FreeStyleProject p = j.jenkins.createProject(FreeStyleProject.class, "sut");
        populateWorkspace(p, "content.txt");
        p.getPublishersList().add(wipeoutPublisher());

        FilePath workspace = p.getLastBuild().getWorkspace();
        workspace.getParent().chmod(0555); // Remove write for parent dir so rename will fail

        workspace.renameTo(workspace.withSuffix("2"));
        assertTrue("Rename operation should fail", workspace.exists());

        FreeStyleBuild build = j.buildAndAssertSuccess(p);
        assertWorkspaceCleanedUp(build);
    }

    @Test
    public void reportCleanupCommandFailure() throws Exception {
        String command = "mkdir %s";

        FreeStyleProject p = j.jenkins.createProject(FreeStyleProject.class, "sut");
        FilePath ws = j.buildAndAssertSuccess(p).getWorkspace();
        FilePath pre = ws.child("pre-build");
        pre.touch(0);
        FilePath post = ws.child("post-build");
        post.touch(0);

        p.getBuildWrappersList().add(new PreBuildCleanup(Collections.<Pattern>emptyList(), false, null, command));
        WsCleanup wsCleanup = new WsCleanup();
        wsCleanup.setNotFailBuild(true);
        wsCleanup.setCleanupMatrixParent(true);
        wsCleanup.setExternalDelete(command);
        p.getPublishersList().add(wsCleanup);

        FreeStyleBuild build = j.buildAndAssertSuccess(p);
        String log = build.getLog();

        assertThat(log, containsString("ERROR: Cleanup command 'mkdir " + pre.getRemote() + "' failed with code 1"));
        assertThat(log, containsString("ERROR: Cleanup command 'mkdir " + post.getRemote() + "' failed with code 1"));
        assertThat(log, containsString("mkdir: cannot create directory"));
        assertThat(log, containsString("File exists"));
    }

    @Test @Issue("JENKINS-28454")
    public void pipelineWorkspaceCleanup() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("" +
                "node { \n" +
                "   ws ('" + ws.getRoot() + "') { \n" +
                "       try { \n" +
                "           writeFile file: 'foo.txt', text: 'foobar' \n" +
                "       } finally { \n" +
                "           step([$class: 'WsCleanup']) \n" +
                "       } \n" +
                "  } \n" +
                "}"));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        j.assertLogContains("[WS-CLEANUP] Deleting project workspace...[WS-CLEANUP] done", run);

        assertThat(ws.getRoot().listFiles(), nullValue());
    }

    @Test @Issue("JENKINS-28454")
    public void pipelineWorkspaceCleanupUsingPattern() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("" +
                "node { \n" +
                "   ws ('" + ws.getRoot() + "') { \n" +
                "       try { \n" +
                "           writeFile file: 'foo.txt', text: 'first file' \n" +
                "           writeFile file: 'bar.txt', text: 'second file' \n" +
                "       } finally { \n" +
                "           step([$class: 'WsCleanup', patterns: [[pattern: 'bar.*', type: 'INCLUDE']]]) \n" +
                "       } \n" +
                "   } \n" +
                "}"));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        j.assertLogContains("[WS-CLEANUP] Deleting project workspace...[WS-CLEANUP] done", run);

        verifyFileExists("foo.txt");
    }

    @Test @Issue("JENKINS-28454")
    public void pipelineWorkspaceCleanupUnlessBuildFails() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("" +
                "node { \n" +
                "   ws ('" + ws.getRoot() + "'){ \n" +
                "       try { \n" +
                "           writeFile file: 'foo.txt', text: 'foobar' \n" +
                "			throw new Exception() \n" +
                "		} catch (err) { \n" +
                "			currentBuild.result = 'FAILURE' \n" +
                "       } finally { \n" +
                "			step ([$class: 'WsCleanup', cleanWhenFailure: false]) \n" +
                "       } \n" +
                "   } \n" +
                "}"));
        WorkflowRun build = p.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, build);
        j.assertLogContains("[WS-CLEANUP] Deleting project workspace...[WS-CLEANUP] Skipped based on build state FAILURE", build);

        verifyFileExists("foo.txt");
    }

    @Test
    @Issue("JENKINS-37054")
    public void symbolAnnotationWorkspaceCleanup() throws Exception {
        assumeSymbolDependencies();

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("" +
                "node { \n" +
                "   ws ('" + ws.getRoot() + "') { \n" +
                "       try { \n" +
                "           writeFile file: 'foo.txt', text: 'foobar' \n" +
                "       } finally { \n" +
                "           cleanWs() \n" +
                "       } \n" +
                "  } \n" +
                "}"));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        j.assertLogContains("[WS-CLEANUP] Deleting project workspace...[WS-CLEANUP] done", run);

        assertThat(ws.getRoot().listFiles(), nullValue());
    }

    @Test
    @Issue("JENKINS-37054")
    public void symbolWorkspaceCleanupAnnotationUsingPattern() throws Exception {
        assumeSymbolDependencies();

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("" +
                "node { \n" +
                "   ws ('" + ws.getRoot() + "') { \n" +
                "       try { \n" +
                "           writeFile file: 'foo.txt', text: 'first file' \n" +
                "           writeFile file: 'bar.txt', text: 'second file' \n" +
                "       } finally { \n" +
                "           cleanWs patterns: [[pattern: 'bar.*', type: 'INCLUDE']] \n" +
                "       } \n" +
                "   } \n" +
                "}"));
        WorkflowRun run = j.assertBuildStatusSuccess(p.scheduleBuild2(0));
        j.assertLogContains("[WS-CLEANUP] Deleting project workspace...[WS-CLEANUP] done", run);

        verifyFileExists("foo.txt");
    }

    @Test
    @Issue("JENKINS-37054")
    public void symbolAnnotationWorkspaceCleanupUnlessBuildFails() throws Exception {
        assumeSymbolDependencies();

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("" +
                "node { \n" +
                "   ws ('" + ws.getRoot() + "'){ \n" +
                "       try { \n" +
                "           writeFile file: 'foo.txt', text: 'foobar' \n" +
                "           throw new Exception() \n" +
                "       } catch (err) { \n" +
                "           currentBuild.result = 'FAILURE' \n" +
                "       } finally { \n" +
                "           cleanWs cleanWhenFailure: false \n" +
                "       } \n" +
                "   } \n" +
                "}"));
        WorkflowRun run = p.scheduleBuild2(0).get();
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("[WS-CLEANUP] Deleting project workspace...[WS-CLEANUP] Skipped based on build state FAILURE", run);

        verifyFileExists("foo.txt");
    }

    /**
     * To use the @Symbol annotation in tests, minimum workflow-cps version 2.10 is required.
     * This dependency comes with other dependency version requirements, as stated by this method.
     * To run tests restricted by this method, type
     * <pre>
     * mvn clean install -Djenkins.version=1.642.1 -Djava.level=7 -Dworkflow-job.version=2.4 -Dworkflow-basic-steps.version=2.1 -Dworkflow-cps.version=2.10 -Dworkflow-durable-task-step.version=2.4
     * </pre>
     */
    private static void assumeSymbolDependencies() {
        assumePropertyIsGreaterThanOrEqualTo(System.getProperty("jenkins.version"), "1.642.1");
        assumePropertyIsGreaterThanOrEqualTo(System.getProperty("java.level"), "7");
        assumePropertyIsGreaterThanOrEqualTo(System.getProperty("workflow-job.version"), "2.4");
        assumePropertyIsGreaterThanOrEqualTo(System.getProperty("workflow-basic-steps.version"), "2.1");
        assumePropertyIsGreaterThanOrEqualTo(System.getProperty("workflow-cps.version"), "2.10");
        assumePropertyIsGreaterThanOrEqualTo(System.getProperty("workflow-durable-task-step.version"), "2.4");
    }

    /**
     * Checks if the given property is not null, and if it's greater than or equal to the given version.
     *
     * @param property the property to be checked
     * @param version  the version on which the property is checked against
     */
    private static void assumePropertyIsGreaterThanOrEqualTo(@CheckForNull String property, @Nonnull String version) {
        assumeThat(property, notNullValue());
        assumeThat(new VersionNumber(property).compareTo(new VersionNumber(version)), is(greaterThanOrEqualTo(0)));
    }

    private void verifyFileExists(String fileName) {
        File[] files = ws.getRoot().listFiles();
        assertThat(files, notNullValue());
        assertThat(files, arrayWithSize(1));
        assertThat(files[0].getName(), is(fileName));
    }

    public static WsCleanup wipeoutPublisher() {
        WsCleanup wsCleanup = new WsCleanup();
        wsCleanup.setNotFailBuild(true);
        wsCleanup.setCleanupMatrixParent(true);

        return wsCleanup;
    }

    private void populateWorkspace(FreeStyleProject p, String filename) throws Exception {
        p.getBuildersList().add(new Shell("touch '" + filename + "'"));
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        p.getBuildersList().clear();
        assertFalse("Workspace populated", b.getWorkspace().list().isEmpty());
    }

    private void assertWorkspaceCleanedUp(AbstractBuild<?, ?> b) throws Exception {
        final FilePath workspace = b.getWorkspace();
        if (workspace == null) return; // removed

        List<FilePath> files = workspace.list();
        if (files == null) return; // removed

        assertTrue("Workspace contains: " + files, files.isEmpty());
    }

    /**
     * Create content in workspace of both master and child builds.
     *
     * @author ogondza
     */
    private static final class MatrixWsPopulator extends BuildWrapper {
        @Override
        public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
            build.getWorkspace().child("content.txt").touch(0);
            if (build instanceof MatrixRun) {
                MatrixBuild mb = ((MatrixRun) build).getParentBuild();
                mb.getWorkspace().child("content.txt").touch(0);
            }

            return new Environment() {};
        }

        @Override
        public Descriptor getDescriptor() {
            return new Descriptor();
        }

        private static final class Descriptor extends hudson.model.Descriptor<BuildWrapper> {
            @Override
            public String getDisplayName() {
                return "Matrix workspace populator";
            }
        }
    }
}
