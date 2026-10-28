Feature: Protected HelloWorld resource
  Scenario: An authorized client receives the greeting
    Given a valid access token with scope "hello.read"
    When the client requests hello
    Then the response status is 200
    And the greeting subject is "subject-1"

  Scenario: Missing credentials are rejected
    Given no access token
    When the client requests hello
    Then the response status is 401
    And the error is "unauthorized"

  Scenario: Insufficient scope is forbidden
    Given a valid access token with scope "other.read"
    When the client requests hello
    Then the response status is 403
    And the error is "forbidden"
