/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.local_json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.resources.MoreResources;
import io.airbyte.integrations.base.AirbyteMessageConsumer;
import io.airbyte.integrations.base.Destination;
import io.airbyte.integrations.base.JavaBaseConstants;
import io.airbyte.integrations.destination.StandardNameTransformer;
import io.airbyte.protocol.models.v0.AirbyteConnectionStatus;
import io.airbyte.protocol.models.v0.AirbyteConnectionStatus.Status;
import io.airbyte.protocol.models.v0.AirbyteMessage;
import io.airbyte.protocol.models.v0.AirbyteRecordMessage;
import io.airbyte.protocol.models.v0.AirbyteStateMessage;
import io.airbyte.protocol.models.v0.CatalogHelpers;
import io.airbyte.protocol.models.v0.ConfiguredAirbyteCatalog;
import io.airbyte.protocol.models.v0.ConnectorSpecification;
import io.airbyte.protocol.models.Field;
import io.airbyte.protocol.models.JsonSchemaType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalJsonDestinationTest {

  private static final Instant NOW = Instant.now();
  private static final Path TEST_ROOT = Path.of("/tmp/airbyte_tests");
  private static final String USERS_STREAM_NAME = "users";
  private static final String TASKS_STREAM_NAME = "tasks";
  private static final String USERS_FILE = new StandardNameTransformer().getRawTableName(USERS_STREAM_NAME) + ".jsonl";
  private static final String TASKS_FILE = new StandardNameTransformer().getRawTableName(TASKS_STREAM_NAME) + ".jsonl";;
  private static final AirbyteMessage MESSAGE_USERS1 = new AirbyteMessage().withType(AirbyteMessage.Type.RECORD)
      .withRecord(new AirbyteRecordMessage().withStream(USERS_STREAM_NAME)
          .withData(Jsons.jsonNode(ImmutableMap.builder().put("name", "john").put("id", "10").build()))
          .withEmittedAt(NOW.toEpochMilli()));
  private static final AirbyteMessage MESSAGE_USERS2 = new AirbyteMessage().withType(AirbyteMessage.Type.RECORD)
      .withRecord(new AirbyteRecordMessage().withStream(USERS_STREAM_NAME)
          .withData(Jsons.jsonNode(ImmutableMap.builder().put("name", "susan").put("id", "30").build()))
          .withEmittedAt(NOW.toEpochMilli()));
  private static final AirbyteMessage MESSAGE_TASKS1 = new AirbyteMessage().withType(AirbyteMessage.Type.RECORD)
      .withRecord(new AirbyteRecordMessage().withStream(TASKS_STREAM_NAME)
          .withData(Jsons.jsonNode(ImmutableMap.builder().put("goal", "announce the game.").build()))
          .withEmittedAt(NOW.toEpochMilli()));
  private static final AirbyteMessage MESSAGE_TASKS2 = new AirbyteMessage().withType(AirbyteMessage.Type.RECORD)
      .withRecord(new AirbyteRecordMessage().withStream(TASKS_STREAM_NAME)
          .withData(Jsons.jsonNode(ImmutableMap.builder().put("goal", "ship some code.").build()))
          .withEmittedAt(NOW.toEpochMilli()));
  private static final AirbyteMessage MESSAGE_STATE = new AirbyteMessage().withType(AirbyteMessage.Type.STATE)
      .withState(new AirbyteStateMessage().withData(Jsons.jsonNode(ImmutableMap.builder().put("checkpoint", "now!").build())));

  private static final ConfiguredAirbyteCatalog CATALOG = new ConfiguredAirbyteCatalog().withStreams(Lists.newArrayList(
      CatalogHelpers.createConfiguredAirbyteStream(USERS_STREAM_NAME, null, Field.of("name", JsonSchemaType.STRING),
          Field.of("id", JsonSchemaType.STRING)),
      CatalogHelpers.createConfiguredAirbyteStream(TASKS_STREAM_NAME, null, Field.of("goal", JsonSchemaType.STRING))));

  private Path destinationPath;
  private JsonNode config;

  @BeforeEach
  void setup() throws IOException {
    destinationPath = Files.createTempDirectory(Files.createDirectories(TEST_ROOT), "test");
    config = Jsons.jsonNode(ImmutableMap.of(LocalJsonDestination.DESTINATION_PATH_FIELD, destinationPath.toString()));
  }

  private LocalJsonDestination getDestination() {
    final LocalJsonDestination result = spy(LocalJsonDestination.class);
    doReturn(destinationPath).when(result).getDestinationPath(any());
    return result;
  }

  @Test
  void testSpec() throws Exception {
    final ConnectorSpecification actual = getDestination().spec();
    final String resourceString = MoreResources.readResource("spec.json");
    final ConnectorSpecification expected = Jsons.deserialize(resourceString, ConnectorSpecification.class);

    assertEquals(expected, actual);
  }

  @Test
  void testCheckSuccess() {
    final AirbyteConnectionStatus actual = getDestination().check(config);
    final AirbyteConnectionStatus expected = new AirbyteConnectionStatus().withStatus(Status.SUCCEEDED);
    assertEquals(expected, actual);
  }

  @Test
  void testCheckFailure() throws IOException {
    final Path looksLikeADirectoryButIsAFile = destinationPath.resolve("file");
    FileUtils.touch(looksLikeADirectoryButIsAFile.toFile());
    final LocalJsonDestination destination = spy(LocalJsonDestination.class);
    doReturn(looksLikeADirectoryButIsAFile).when(destination).getDestinationPath(any());
    final JsonNode config = Jsons.jsonNode(ImmutableMap.of(LocalJsonDestination.DESTINATION_PATH_FIELD, looksLikeADirectoryButIsAFile.toString()));
    final AirbyteConnectionStatus actual = destination.check(config);
    final AirbyteConnectionStatus expected = new AirbyteConnectionStatus().withStatus(Status.FAILED);

    // the message includes the random file path, so just verify it exists and then remove it when we do
    // rest of the comparison.
    assertNotNull(actual.getMessage());
    actual.setMessage(null);
    assertEquals(expected, actual);
  }

  @Test
  void testCheckInvalidDestinationFolder() {
    final Path relativePath = Path.of("../tmp/conf.d/");
    final JsonNode config = Jsons.jsonNode(ImmutableMap.of(LocalJsonDestination.DESTINATION_PATH_FIELD, relativePath.toString()));
    final AirbyteConnectionStatus actual = new LocalJsonDestination().check(config);
    final AirbyteConnectionStatus expected = new AirbyteConnectionStatus().withStatus(Status.FAILED);
    // the message includes the random file path, so just verify it exists and then remove it when we do
    // rest of the comparison.
    assertNotNull(actual.getMessage());
    actual.setMessage(null);
    assertEquals(expected, actual);
  }

  @Test
  void testWriteSuccess() throws Exception {
    final AirbyteMessageConsumer consumer = getDestination().getConsumer(config, CATALOG, Destination::defaultOutputRecordCollector);

    consumer.accept(MESSAGE_USERS1);
    consumer.accept(MESSAGE_TASKS1);
    consumer.accept(MESSAGE_USERS2);
    consumer.accept(MESSAGE_TASKS2);
    consumer.accept(MESSAGE_STATE);
    consumer.close();

    final List<JsonNode> usersActual = toJson(destinationPath.resolve(USERS_FILE))
        .map(o -> o.get(JavaBaseConstants.COLUMN_NAME_DATA))
        .collect(Collectors.toList());

    assertTrue(usersActual.contains(MESSAGE_USERS1.getRecord().getData()));
    assertTrue(usersActual.contains(MESSAGE_USERS2.getRecord().getData()));

    final List<JsonNode> tasksActual = toJson(destinationPath.resolve(TASKS_FILE))
        .map(o -> o.get(JavaBaseConstants.COLUMN_NAME_DATA))
        .collect(Collectors.toList());

    assertTrue(tasksActual.contains(MESSAGE_TASKS1.getRecord().getData()));
    assertTrue(tasksActual.contains(MESSAGE_TASKS2.getRecord().getData()));

    // verify tmp files are cleaned up
    final Set<String> actualFilenames = Files.list(destinationPath)
        .map(Path::getFileName)
        .map(Path::toString)
        .collect(Collectors.toSet());

    assertEquals(Sets.newHashSet(USERS_FILE, TASKS_FILE), actualFilenames);
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  @Test
  void testWriteFailure() throws Exception {
    // hack to force an exception to be thrown from within the consumer.
    final AirbyteMessage spiedMessage = spy(MESSAGE_USERS1);
    doThrow(new RuntimeException()).when(spiedMessage).getRecord();

    final AirbyteMessageConsumer consumer = spy(getDestination().getConsumer(config, CATALOG, Destination::defaultOutputRecordCollector));

    assertThrows(RuntimeException.class, () -> consumer.accept(spiedMessage));
    consumer.accept(MESSAGE_USERS2);
    assertThrows(IOException.class, consumer::close);

    // verify tmp files are cleaned up and no files are output at all
    final Set<String> actualFilenames = Files.list(destinationPath).map(Path::getFileName).map(Path::toString).collect(Collectors.toSet());
    assertEquals(Collections.emptySet(), actualFilenames);
  }

  private Stream<JsonNode> toJson(final Path path) throws IOException {
    return Files.readAllLines(path).stream()
        .map(Jsons::deserialize);
  }

}
