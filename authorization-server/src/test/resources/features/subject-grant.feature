Feature: Subject grant authentication
  The authorization server issues an access token only to an authenticated configured user

  Scenario: Valid registered client and matching user receive a token
    Given the authorization server is running
    When a valid subject grant is submitted
    Then a signed access token is returned

  Scenario: Wrong password is rejected
    Given the authorization server is running
    When a subject grant is submitted with an invalid identity
    Then the token request is rejected without exposing credentials

  Scenario: Unknown client is rejected
    Given the authorization server is running
    When a subject grant is submitted by an unknown client
    Then the client authentication is rejected without exposing credentials
