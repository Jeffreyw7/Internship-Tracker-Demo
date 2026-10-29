package com.campus.tracker.application.importing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="campus.imports.cleanup-enabled",havingValue="true",matchIfMissing=true)
public class ImportSchedulingConfiguration {}
