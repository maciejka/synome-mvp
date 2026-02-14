package com.sky.synome.api;

import static io.restassured.RestAssured.given;

import io.restassured.specification.RequestSpecification;

public final class ApiTestAuth {

  public static final String TEST_ADMIN_API_KEY = "test-local-api-key";

  private ApiTestAuth() {}

  public static RequestSpecification givenAuthorized() {
    return given().header("X-Api-Key", TEST_ADMIN_API_KEY);
  }
}
