Feature: Access Control

  Scenario Outline: Request to protected resource is redirected to login page when accessed without login
    Given a reaper service with access control enabled is running
    When a <path> <request> is made
    Then the response was redirected to the login page
    Examples:
      | path   | request              |
      | GET    | /cluster             |
      | POST   | /cluster             |
      | GET    | /repair_run          |
      | GET    | /repair_schedule     |

  Scenario Outline: Request to public resource is allowed without login
    Given a reaper service with access control enabled is running
    When a <path> <request> is made
    Then a "OK" response is returned
    Examples:
      | path | request |
      | GET  | /ping   |

  Scenario Outline: Request to protected resource is allowed when access control is disabled
    Given a reaper service is running
    When a <path> <request> is made
    Then a "OK" response is returned
    Examples:
      | path | request |
      | GET    | /cluster             |
      | GET    | /repair_run          |
      | GET    | /repair_schedule     |
