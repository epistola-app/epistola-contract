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
        new GenerateDocumentRequest(varNamespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), varVersion: null).Validate();
        Assert.Throws<ArgumentException>(() =>
            new GenerateDocumentRequest(varNamespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), varVersion: "").Validate());

        new GenerateDocumentRequest(varNamespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), environmentKey: null).Validate();
        Assert.Throws<ArgumentException>(() =>
            new GenerateDocumentRequest(varNamespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), environmentKey: "PROD").Validate());

        new GenerateDocumentRequest(varNamespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), filename: new string('x', 255)).Validate();
        var ex = Assert.Throws<ArgumentException>(() =>
            new GenerateDocumentRequest(varNamespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english", data: new Dictionary<string, object>(), filename: new string('x', 256)).Validate());
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
            varNamespace: "acme-corp", catalogKey: "default", templateKey: "invoice", variantKey: "english",
            data: new Dictionary<string, object> { ["n"] = i })).ToList();
        var request = new GenerateBatchRequest(items: items);
        Assert.Same(request, request.Validate());
    }

    // A release number is a plain MAJOR.MINOR.PATCH (ADR 0002, amendment 2): long-term work is not
    // released, so there are no pre-release numbers.
    [Fact]
    public void ReleaseNumberAcceptsAPlainSemVerNumber()
    {
        var release = new ReleaseCatalogDraftRequest(varVersion: "1.4.0");
        Assert.Same(release, release.Validate());
        var draft = new CreateCatalogDraftRequest(name: "Follow-up", from: "1.4.0");
        Assert.Same(draft, draft.Validate());
    }

    [Theory]
    [InlineData("1.4")]
    [InlineData("v1.4.0")]
    [InlineData("1.4.0+build")]
    [InlineData("2.0.0-beta.1")]
    [InlineData("2.0.0-redesign-2027.1")]
    [InlineData("next")]
    public void ReleaseNumberRejectsAnythingElse(string version)
    {
        Assert.Throws<ArgumentException>(() => new ReleaseCatalogDraftRequest(varVersion: version).Validate());
        Assert.Throws<ArgumentException>(() => new CreateCatalogDraftRequest(name: "Follow-up", from: version).Validate());
    }

    [Fact]
    public void PromoteRequestNamesThePreviousNextVersionWithinBounds()
    {
        new PromoteCatalogDraftRequest().Validate();
        new PromoteCatalogDraftRequest(keepPreviousAs: "Before the redesign", acknowledgedBehind: new List<string> { "1.5.0" }).Validate();
        Assert.Throws<ArgumentException>(() => new PromoteCatalogDraftRequest(keepPreviousAs: "").Validate());
        Assert.Throws<ArgumentException>(() => new PromoteCatalogDraftRequest(keepPreviousAs: new string('x', 101)).Validate());
    }

    // An environment override names an exact release or a draft (ADR 0002, amendment 2).
    [Fact]
    public void SetEnvironmentOverrideRequestTakesAnExactReleaseOrADraft()
    {
        new SetEnvironmentOverrideRequest(releaseVersion: "1.3.0").Validate();
        new SetEnvironmentOverrideRequest(catalogVersion: "next").Validate();
        new SetEnvironmentOverrideRequest(catalogVersion: "0193a6b2-8a10-7d2e-8b3c-4d5e6f708192").Validate();
    }

    [Theory]
    [InlineData("^1.3.0")]
    [InlineData("1.3")]
    [InlineData("2.0.0-beta.1")]
    [InlineData("next")]
    public void SetEnvironmentOverrideRequestRejectsAnyOtherRelease(string release)
    {
        var ex = Assert.Throws<ArgumentException>(() => new SetEnvironmentOverrideRequest(releaseVersion: release).Validate());
        Assert.Contains("releaseVersion", ex.Message);
    }

    [Theory]
    [InlineData("1.3.0")]
    [InlineData("Next")]
    [InlineData("draft-1")]
    public void SetEnvironmentOverrideRequestRejectsAnyOtherDraft(string draft)
    {
        var ex = Assert.Throws<ArgumentException>(() => new SetEnvironmentOverrideRequest(catalogVersion: draft).Validate());
        Assert.Contains("catalogVersion", ex.Message);
    }

    [Fact]
    public void ValidateReturnsSameInstanceForFluentChaining()
    {
        var request = new CreateTenantRequest(key: "acme-corp", name: "Acme Corporation");
        Assert.Same(request, request.Validate());
    }
}
