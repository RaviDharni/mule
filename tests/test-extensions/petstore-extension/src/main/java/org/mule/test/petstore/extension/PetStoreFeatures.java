/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.petstore.extension;

import org.mule.runtime.api.config.Feature;

public enum PetStoreFeatures implements Feature {

  LEGACY_FEATURE("Feature legacy, just for testing", "MULE-123", "4.4.0");

  private final String issue;
  private final String since;

  private final String description;

  PetStoreFeatures(String description, String issue, String since) {
    this.description = description;
    this.issue = issue;
    this.since = since;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public String getIssueId() {
    return issue;
  }

  @Override
  public String getSince() {
    return since;
  }
}
