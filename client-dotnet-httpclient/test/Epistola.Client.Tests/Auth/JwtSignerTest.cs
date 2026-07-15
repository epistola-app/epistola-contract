using System;
using System.IdentityModel.Tokens.Jwt;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using Epistola.Client.Auth;
using Xunit;

namespace Epistola.Client.Tests.Auth;

public class JwtSignerTest
{
    private static RSA NewRsaKey() => RSA.Create(2048);

    private static JwtSecurityToken Parse(string token) => new JwtSecurityTokenHandler().ReadJwtToken(token);

    [Fact]
    public void CreateTokenProducesValidJwtWithCorrectClaims()
    {
        var signer = JwtSigner.Builder().ConsumerId("invoice-service").PrivateKey(NewRsaKey()).Build();
        var jwt = Parse(signer.CreateToken());

        Assert.Equal("invoice-service", jwt.Issuer);
        Assert.Contains(jwt.Claims, c => c.Type == "iat");
        Assert.Contains(jwt.Claims, c => c.Type == "jti");
        Assert.True(jwt.ValidTo > jwt.ValidFrom);
    }

    [Fact]
    public void TokenExpiryMatchesConfiguredLifetime()
    {
        var signer = JwtSigner.Builder()
            .ConsumerId("test-app")
            .PrivateKey(NewRsaKey())
            .TokenLifetime(TimeSpan.FromSeconds(30))
            .Build();

        var jwt = Parse(signer.CreateToken());
        Assert.Equal(30, Math.Round((jwt.ValidTo - jwt.ValidFrom).TotalSeconds));
    }

    [Fact]
    public void DefaultTokenLifetimeIs60Seconds()
    {
        var signer = JwtSigner.Builder().ConsumerId("test-app").PrivateKey(NewRsaKey()).Build();
        var jwt = Parse(signer.CreateToken());
        Assert.Equal(60, Math.Round((jwt.ValidTo - jwt.ValidFrom).TotalSeconds));
    }

    [Fact]
    public void EachTokenHasAUniqueJti()
    {
        var signer = JwtSigner.Builder().ConsumerId("test-app").PrivateKey(NewRsaKey()).Build();
        var jti1 = Parse(signer.CreateToken()).Claims.First(c => c.Type == "jti").Value;
        var jti2 = Parse(signer.CreateToken()).Claims.First(c => c.Type == "jti").Value;
        Assert.NotEqual(jti1, jti2);
    }

    [Fact]
    public void EcKeyProducesEs256Token()
    {
        var signer = JwtSigner.Builder().ConsumerId("ec-app").PrivateKey(ECDsa.Create(ECCurve.NamedCurves.nistP256)).Build();
        var jwt = Parse(signer.CreateToken());
        Assert.Equal("ES256", jwt.Header.Alg);
    }

    [Fact]
    public void RsaKeyProducesRs256Token()
    {
        var signer = JwtSigner.Builder().ConsumerId("rsa-app").PrivateKey(NewRsaKey()).Build();
        Assert.Equal("RS256", Parse(signer.CreateToken()).Header.Alg);
    }

    [Fact]
    public async Task HandlerSetsAuthorizationBearerHeader()
    {
        var signer = JwtSigner.Builder().ConsumerId("test-app").PrivateKey(NewRsaKey()).Build();
        var stub = new StubHttpMessageHandler(_ => new HttpResponseMessage(HttpStatusCode.OK));
        var handler = signer.Handler();
        handler.InnerHandler = stub;
        var client = new HttpClient(handler) { BaseAddress = new Uri("http://localhost/") };

        await client.PostAsync("thing", new StringContent("{}", Encoding.UTF8, "application/json"));

        var auth = Assert.Single(stub.Requests).Headers.Authorization;
        Assert.NotNull(auth);
        Assert.Equal("Bearer", auth!.Scheme);
        Assert.Equal("test-app", Parse(auth.Parameter!).Issuer);
    }

    [Fact]
    public void RoundTripPemPreservesKey()
    {
        using var rsa = NewRsaKey();
        var pem = rsa.ExportPkcs8PrivateKeyPem();
        var parsed = JwtSigner.ParsePrivateKeyPem(pem);
        Assert.IsAssignableFrom<RSA>(parsed);
    }

    [Fact]
    public void BuilderRejectsBlankConsumerId()
    {
        Assert.Throws<ArgumentException>(() => JwtSigner.Builder().ConsumerId(""));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    public void BuilderRejectsNonPositiveLifetime(int seconds)
    {
        Assert.Throws<ArgumentException>(() => JwtSigner.Builder().TokenLifetime(TimeSpan.FromSeconds(seconds)));
    }

    [Fact]
    public void BuilderRequiresConsumerId()
    {
        Assert.Throws<InvalidOperationException>(() => JwtSigner.Builder().PrivateKey(NewRsaKey()).Build());
    }

    [Fact]
    public void BuilderRequiresPrivateKey()
    {
        Assert.Throws<InvalidOperationException>(() => JwtSigner.Builder().ConsumerId("test-app").Build());
    }
}
