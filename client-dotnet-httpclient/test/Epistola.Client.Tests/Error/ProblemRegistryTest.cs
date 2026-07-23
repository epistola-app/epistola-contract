using Epistola.Client.Error;
using Xunit;

namespace Epistola.Client.Tests.Error;

/// <summary>
/// Guards the generated <c>KnownProblemSlugs</c> (from the spec's <c>x-problem-types</c> extension)
/// against the hand-written <see cref="ProblemTypes"/>. Generation makes drift from the spec
/// impossible; these tests catch the remaining hand-written pieces drifting from the generated data.
/// </summary>
public class ProblemRegistryTest
{
    [Fact]
    public void GeneratedRegistryBaseMatchesHandWrittenTypeBase()
    {
        Assert.Equal(ProblemTypes.TypeBase, GeneratedProblemType.Base);
    }

    [Fact]
    public void CanonicalSlugsArePresentWithDocumentedValues()
    {
        Assert.Equal("validation-error", KnownProblemSlugs.VALIDATION_ERROR);
        Assert.Equal("bad-request", KnownProblemSlugs.BAD_REQUEST);
        Assert.Equal("unauthorized", KnownProblemSlugs.UNAUTHORIZED);
        Assert.Equal("api-key-auth-disabled", KnownProblemSlugs.API_KEY_AUTH_DISABLED);
        Assert.Equal("forbidden", KnownProblemSlugs.FORBIDDEN);
        Assert.Equal("not-found", KnownProblemSlugs.NOT_FOUND);
        Assert.Equal("conflict", KnownProblemSlugs.CONFLICT);
        Assert.Equal("data-model-validation-error", KnownProblemSlugs.DATA_MODEL_VALIDATION_ERROR);
        Assert.Equal("rate-limited", KnownProblemSlugs.RATE_LIMITED);
    }
}
