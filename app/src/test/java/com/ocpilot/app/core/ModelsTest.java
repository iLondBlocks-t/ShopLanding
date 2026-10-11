package com.ocpilot.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ModelsTest {
    @Test public void healthPairAndRotation() throws Exception {
        Models.Health h = Models.Health.fromJson(Fixtures.object("gateway-health.json")); assertTrue(h.ok); assertTrue(h.running); assertEquals(3600,h.uptimeSec);
        Models.Pairing p = Models.Pairing.fromJson(Fixtures.object("gateway-pair.json")); assertEquals("device_example",p.deviceId); assertTrue(p.deviceToken.startsWith("dt_"));
        assertTrue(Models.Rotation.fromJson(Fixtures.object("gateway-rotate.json")).deviceToken.startsWith("dt_"));
    }
    @Test public void status() throws Exception { Models.Status s = Models.Status.fromJson(Fixtures.object("gateway-status.json")); assertEquals("1.0.0",s.gatewayVersion); assertTrue(s.java); assertTrue(s.androidSdk); assertEquals(12000,s.freeDiskMB); assertEquals("",s.lastError); assertTrue(s.github.connected); }
    @Test public void github() throws Exception { assertEquals("octocat", Models.Github.fromJson(Fixtures.object("gateway-github.json")).login); Models.Github g = Models.Github.fromJson(Fixtures.object("gateway-github-disconnected.json")); assertFalse(g.connected); assertEquals("",g.login); }
    @Test public void devices() throws Exception { Models.Device d = Models.list(Fixtures.array("gateway-devices.json"),Models.Device::fromJson).get(0); assertTrue(d.current); assertEquals("Pixel 6",d.name); }
    @Test public void repositoriesAndCreateResponse() throws Exception { Models.Repo r = Models.list(Fixtures.array("gateway-repos.json"), Models.Repo::fromJson).get(0); assertTrue(r.isPrivate); assertEquals("main",r.defaultBranch); assertEquals("https://github.com/octocat/demo.git",Models.Repo.fromJson(Fixtures.object("gateway-repo-created.json")).cloneUrl); }
    @Test public void projectListCreatePatchAndNulls() throws Exception {
        assertEquals(1,Models.list(Fixtures.array("gateway-projects.json"), Models.Project::fromJson).size());
        Models.Project p = Models.Project.fromJson(Fixtures.object("gateway-project.json")); assertEquals("Demo",p.name); assertEquals(p.name,Models.Project.fromJson(p.toJson()).name);
        Models.Project empty = Models.Project.fromJson(Fixtures.object("gateway-project-empty.json")); assertEquals("",empty.repoFullName); assertEquals("",empty.branch);
    }
    @Test public void git() throws Exception { Models.Git g = Models.Git.fromJson(Fixtures.object("gateway-git.json")); assertTrue(g.dirty); assertEquals(1,g.ahead); assertEquals(1,g.lastCommits.size()); }
    @Test public void everyArtifactKind() throws Exception {
        List<Models.Artifact> artifacts = Models.list(Fixtures.array("gateway-artifacts.json"),Models.Artifact::fromJson); assertEquals(6,artifacts.size());
        for (Models.Artifact a : artifacts) { assertEquals(64,a.sha256.length()); assertEquals(123456,a.size); assertEquals(a.sha256,Models.Artifact.fromJson(a.toJson()).sha256); }
        assertTrue(artifacts.get(0).isApk()); assertFalse(artifacts.get(3).isApk());
    }
    @Test public void noContentAndAllContractErrorBodies() throws Exception {
        assertEquals("",Fixtures.text("http-no-content.txt")); JSONArray errors = Fixtures.array("gateway-errors.json"); assertEquals(21,errors.length());
        for (int i=0;i<errors.length();i++) { JSONObject error = errors.getJSONObject(i); assertFalse(Models.string(error,"error").isEmpty()); assertFalse(Models.string(error,"message").isEmpty()); }
    }
    @Test public void realOpenCodeResponses() throws Exception {
        assertEquals("ses_main",Models.Session.fromJson(Fixtures.object("oc-session.json")).id);
        assertEquals(1,Models.list(Fixtures.array("oc-sessions.json"),Models.Session::fromJson).size());
        List<Models.Diff> diffs = Models.list(Fixtures.array("oc-diff.json"),Models.Diff::fromJson); assertTrue(diffs.get(0).patch.contains("+new")); assertEquals(1,diffs.get(0).additions);
        List<Models.ModelChoice> models = Models.ModelChoice.fromProviders(Fixtures.object("oc-providers.json")); assertEquals("openai",models.get(0).providerID); assertEquals("gpt-4.1",models.get(0).modelID); assertEquals(2,models.get(0).toJson().length());
        assertTrue(Models.ModelChoice.fromProviders(Fixtures.object("oc-providers-empty.json")).isEmpty());
    }
    @Test public void missingOptionalsAndNullValuesNeverCrash() throws Exception {
        JSONObject missing = new JSONObject(), nulls = new JSONObject("{\"version\":null,\"repoFullName\":null,\"scopes\":null,\"models\":null,\"opencode\":null,\"tools\":null}");
        for (JSONObject j : new JSONObject[]{missing,nulls}) { Models.Health.fromJson(j); Models.Status.fromJson(j); Models.Github.fromJson(j); Models.Pairing.fromJson(j); Models.Rotation.fromJson(j); Models.Device.fromJson(j); Models.Repo.fromJson(j); Models.Project.fromJson(j); Models.Artifact.fromJson(j); Models.Git.fromJson(j); Models.Commit.fromJson(j); Models.Session.fromJson(j); Models.Diff.fromJson(j); Models.ModelChoice.fromProviders(j); }
        assertEquals("", Models.string(null,"key")); assertTrue(Models.list(new JSONArray("[null,5,{}]"),Models.Project::fromJson).size()==1);
    }
    @Test public void timestamps() { assertEquals(1791666000000L,Models.timestamp("2026-10-10T21:00:00Z")); assertEquals(12345000L,Models.timestamp("12345")); assertEquals(0,Models.timestamp("bad")); }
}
