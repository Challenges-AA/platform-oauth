Feature: Authorization Code with PKCE S256
  Background:
    Given the authorization server is running

  Scenario: A public client exchanges its code with the correct verifier
    When a valid PKCE code is exchanged
    Then a signed access token and refresh token are returned

  Scenario: A stolen code cannot be exchanged without the verifier
    When a PKCE code is exchanged with the wrong verifier
    Then the PKCE exchange is rejected without exposing credentials

  Scenario: An unknown client cannot exchange a code
    When a PKCE code is exchanged by an unknown client
    Then client authentication is rejected without exposing credentials
