// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

using System;
using System.Collections.Generic;
using System.Linq;
using Epistola.Client.Model;
using Epistola.Client.Validation;
using Xunit;

namespace Epistola.Client.Tests.Validation;

/// <summary>
/// Exercises the build-time generated <c>Validate()</c> extension methods (ModelValidation.g.cs),
/// mirroring the Kotlin ModelValidationTest.
/// </summary>
public class ModelValidationTest
{
    [Fact]
    public void AnAttributeCriterionNamesItsAttributeByResourceKeyNeverDotted()
    {
        var qualified = new VariantSelectionAttribute(key: "locale", value: "nl-NL", catalogKey: "system");
        Assert.Same(qualified, qualified.Validate());
        // catalogKey is an alias, under the catalog key rule.
        Assert.Throws<ArgumentException>(() => new VariantSelectionAttribute(key: "locale", value: "nl-NL", catalogKey: "System").Validate());
        var ownCatalog = new VariantSelectionAttribute(key: "2026-campaign", value: "spring");
        Assert.Same(ownCatalog, ownCatalog.Validate());
        Assert.Throws<ArgumentException>(() => new VariantSelectionAttribute(key: "system.locale", value: "nl-NL").Validate());
    }

    [Theory]
    [InlineData("abc")]
    [InlineData("a1b")]
    [InlineData("test-env")]
    [InlineData("my-long-key-123")]
    public void KeyPatternAccepts(string key)
    {
        var request = new CreateTenantRequest(key: key, name: "Test");
        Assert.Same(request, request.Validate());
    }

    [Theory]
    [InlineData("ABC")]
    [InlineData("1abc")]
    [InlineData("-abc")]
    [InlineData("abc-")]
    [InlineData("ab--c")]
    [InlineData("ab c")]
    [InlineData("ab.c")]
    public void KeyPatternRejects(string key)
    {
        Assert.Throws<ArgumentException>(() => new CreateTenantRequest(key: key, name: "Test").Validate());
    }

    [Fact]
    public void KeyAtExactMinimumLengthPasses()
    {
        var request = new CreateTenantRequest(key: "abc", name: "Tenant");
        Assert.Same(request, request.Validate());
    }

    [Fact]
    public void KeyOneBelowMinimumLengthFails()
    {
        var ex = Assert.Throws<ArgumentException>(() => new CreateTenantRequest(key: "ab", name: "Tenant").Validate());
        Assert.Contains("key: length must be between 3 and 63", ex.Message);
    }

    [Fact]
    public void KeyAtExactMaximumLengthPasses()
    {
        var key = "a" + new string('b', 62);
        var request = new CreateTenantRequest(key: key, name: "Tenant");
        Assert.Same(request, request.Validate());
    }

    [Fact]
    public void NameBoundaryValues()
    {
        new CreateTenantRequest(key: "abc", name: "x").Validate();
        new CreateTenantRequest(key: "abc", name: new string('x', 255)).Validate();
        Assert.Throws<ArgumentException>(() => new CreateTenantRequest(key: "abc", name: "").Validate());
        Assert.Throws<ArgumentException>(() => new CreateTenantRequest(key: "abc", name: new string('x', 256)).Validate());
    }

    [Fact]
    public void UpdateTenantRequestWithNullNameSkipsValidation()
    {
        var request = new UpdateTenantRequest(name: null);
        Assert.Same(request, request.Validate());
    }

    [Fact]
    public void UpdateTenantRequestWithEmptyNameFails()
    {
        Assert.Throws<ArgumentException>(() => new UpdateTenantRequest(name: "").Validate());
    }

    [Fact]
    public void CreateEnvironmentRequestEnforcesKeyMaxLengthOf30()
    {
        var valid = new CreateEnvironmentRequest(key: "a" + new string('b', 29), name: "Env");
        Assert.Same(valid, valid.Validate());
        var ex = Assert.Throws<ArgumentException>(() => new CreateEnvironmentRequest(key: new string('a', 31), name: "Env").Validate());
        Assert.Contains("key: length must be between 3 and 30", ex.Message);
    }

    [Fact]
    public void GenerateDocumentRequestValidatesNullableFieldsIndependently()
    {
        new GenerateDocumentRequest(@namespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), versionRange: null).Validate();
        Assert.Throws<ArgumentException>(() =>
            new GenerateDocumentRequest(@namespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), versionRange: "").Validate());

        new GenerateDocumentRequest(@namespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), environmentKey: null).Validate();
        Assert.Throws<ArgumentException>(() =>
            new GenerateDocumentRequest(@namespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), environmentKey: "PROD").Validate());

        new GenerateDocumentRequest(@namespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), filename: new string('x', 255)).Validate();
        var ex = Assert.Throws<ArgumentException>(() =>
            new GenerateDocumentRequest(@namespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), filename: new string('x', 256)).Validate());
        Assert.Contains("filename: length must be at most 255", ex.Message);
    }

    [Fact]
    public void GenerateBatchRequestWithEmptyItemsFails()
    {
        var ex = Assert.Throws<ArgumentException>(() => new GenerateBatchRequest(items: new List<BatchGenerationItem>()).Validate());
        Assert.Contains("items: must have at least 1 item(s)", ex.Message);
    }

    [Fact]
    public void GenerateBatchRequestWithItemsPasses()
    {
        var items = Enumerable.Range(1, 5).Select(i => new BatchGenerationItem(
            @namespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english",
            data: new Dictionary<string, object> { ["n"] = i })).ToList();
        var request = new GenerateBatchRequest(items: items);
        Assert.Same(request, request.Validate());
    }

    // A version is a plain MAJOR.MINOR.PATCH, or a patch release labelled with a namespace and a
    // sequence number from 1; there are no other pre-release versions.
    [Theory]
    [InlineData("1.4.0")]
    [InlineData("1.3.1-gemeente-x.1")]
    [InlineData("2.0.0-redesign-2027.12")]
    public void VersionAcceptsASemVerCoreNumberOrAPatchRelease(string version)
    {
        var release = new ReleaseCatalogDraftRequest(version: version);
        Assert.Same(release, release.Validate());
        var draft = new CreateCatalogReleaseRequest(name: "Follow-up", from: version);
        Assert.Same(draft, draft.Validate());
    }

    [Theory]
    [InlineData("1.4")]
    [InlineData("v1.4.0")]
    [InlineData("1.4.0+build")]
    [InlineData("2.0.0-rc.1")]
    [InlineData("1.3.1-gemeente-x.0")]
    [InlineData("1.3.1-gemeente-x.01")]
    [InlineData("1.3.1-Gemeente-x.1")]
    [InlineData("1.3.1-gemeente-x")]
    [InlineData("Next")]
    public void VersionRejectsAnythingElse(string version)
    {
        Assert.Throws<ArgumentException>(() => new ReleaseCatalogDraftRequest(version: version).Validate());
        Assert.Throws<ArgumentException>(() => new CreateCatalogReleaseRequest(name: "Follow-up", from: version).Validate());
    }

    // A draft starts from a version or from another release's releaseId.
    [Fact]
    public void CreateCatalogReleaseRequestStartsFromAReleaseId()
    {
        var draft = new CreateCatalogReleaseRequest(name: "Redesign 2027", from: "k7m2x9qa");
        Assert.Same(draft, draft.Validate());
    }

    // A declaration may give the catalog an alias, under the catalog key rule.
    [Fact]
    public void DependencyDeclarationMayGiveAnAlias()
    {
        var aliased = new CatalogDependencyRequirement(alias: "shared-branding", @namespace: "acme-corp", catalogKey: "branding", version: "1.3.0");
        Assert.Same(aliased, aliased.Validate());
        Assert.Throws<ArgumentException>(() => new CatalogDependencyRequirement(alias: "x", @namespace: "acme-corp", catalogKey: "branding", version: "1.3.0").Validate());
    }

    [Fact]
    public void DependencyDeclarationPinsAnExactRelease()
    {
        var pinned = new CatalogDependencyRequirement(@namespace: "epistola", catalogKey: "system", version: "1.3.0");
        Assert.Same(pinned, pinned.Validate());
        foreach (var release in new[] { "^1.3.0", "1.x", "*", "01.3.0", "1.3.0-rc.1" })
        {
            var declared = new CatalogDependencyRequirement(@namespace: "epistola", catalogKey: "system", version: release);
            Assert.Throws<ArgumentException>(() => declared.Validate());
        }
    }

    // In a draft a dependency declaration may name another catalog's draft instead of a release.
    [Fact]
    public void DependencyDeclarationMayNameADraft()
    {
        var onDraft = new CatalogDependencyRequirement(@namespace: "acme-corp", catalogKey: "branding",
            releaseId: "r4t8w2nc");
        Assert.Same(onDraft, onDraft.Validate());
    }

    [Fact]
    public void ValidateReturnsSameInstanceForFluentChaining()
    {
        var request = new CreateTenantRequest(key: "acme-corp", name: "Acme Corporation");
        Assert.Same(request, request.Validate());
    }

    private static CatalogDto CatalogWithFingerprint(string fingerprint) => new(
        registry: "exchange.epistola.app", @namespace: "acme-corp", catalogKey: "letters", name: "Letters",
        editable: true, source: "exchange:acme-corp/letters", fingerprint: fingerprint);

    // A fingerprint names its algorithm version: v<N>:<64 lowercase hex>.
    [Fact]
    public void FingerprintAcceptsAVersionedDigest()
    {
        var catalog = CatalogWithFingerprint("v1:" + new string('a', 64));
        Assert.Same(catalog, catalog.Validate());
    }

    [Theory]
    [InlineData("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
    [InlineData("v0:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
    [InlineData("v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")]
    [InlineData("v1:aaaa")]
    public void FingerprintRejectsABareOrMalformedDigest(string fingerprint)
    {
        var ex = Assert.Throws<ArgumentException>(() => CatalogWithFingerprint(fingerprint).Validate());
        Assert.Contains("fingerprint", ex.Message);
    }

    private static CatalogDto CatalogWithContentHash(string contentHash) => new(
        registry: "exchange.epistola.app", @namespace: "acme-corp", catalogKey: "letters", name: "Letters",
        editable: true, source: "exchange:acme-corp/letters", fingerprint: "v5:" + new string('a', 64), contentHash: contentHash);

    // A content hash is the V6 fingerprint: v6:<64 lowercase hex>, whatever algorithm the fingerprint names.
    [Fact]
    public void ContentHashAcceptsAV6Digest()
    {
        var catalog = CatalogWithContentHash("v6:" + new string('a', 64));
        Assert.Same(catalog, catalog.Validate());
    }

    [Theory]
    [InlineData("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
    [InlineData("v5:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
    [InlineData("v7:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
    [InlineData("v6:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")]
    [InlineData("v6:aaaa")]
    public void ContentHashRejectsAnythingButAV6Digest(string contentHash)
    {
        var ex = Assert.Throws<ArgumentException>(() => CatalogWithContentHash(contentHash).Validate());
        Assert.Contains("contentHash", ex.Message);
    }
}
