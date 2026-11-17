package com.socialapp.feed.infrastructure.persistence;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Test support: imports the package-private JDBC hashtag/mention adapters into slice tests of other packages. */
@TestConfiguration(proxyBeanMethods = false)
@Import({JdbcPostHashtagRepository.class, JdbcPostMentionRepository.class})
public class TextIndexAdapters {
}
