package jdocs.home.query;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.persistence.query.NoOffset;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.stream.javadsl.Sink;
import java.util.List;

// #readJournalFor
import org.apache.pekko.persistence.query.PersistenceQuery;
import org.apache.pekko.persistence.r2dbc.query.javadsl.R2dbcReadJournal;

// #readJournalFor

// #durableStateStoreFor
import org.apache.pekko.persistence.r2dbc.state.javadsl.R2dbcDurableStateStore;
import org.apache.pekko.persistence.state.DurableStateStoreRegistry;

// #durableStateStoreFor

// #currentEventsBySlices
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.persistence.query.typed.EventEnvelope;

// #currentEventsBySlices

// #currentChangesBySlices
import org.apache.pekko.persistence.query.DurableStateChange;
import org.apache.pekko.persistence.query.UpdatedDurableState;

// #currentChangesBySlices

public class QueryDocCompileOnly {

  interface MyEvent {}

  interface MyState {}

  private final ActorSystem<?> system = ActorSystem.create(Behaviors.empty(), "Docs");

  // #readJournalFor
  R2dbcReadJournal eventQueries =
      PersistenceQuery.get(system)
          .getReadJournalFor(R2dbcReadJournal.class, R2dbcReadJournal.Identifier());
  // ##readJournalFor

  // #durableStateStoreFor
  R2dbcDurableStateStore<MyState> stateQueries =
      DurableStateStoreRegistry.get(system)
          .getDurableStateStoreFor(
              R2dbcDurableStateStore.class, R2dbcDurableStateStore.Identifier());
  // #durableStateStoreFor

  void exampleEventsByPid() {
    // #currentEventsByPersistenceId
    PersistenceId persistenceId = PersistenceId.of("MyEntity", "id1");
    eventQueries
        .currentEventsByPersistenceId(persistenceId.id(), 1, 101)
        .map(envelope -> "event with seqNr " + envelope.sequenceNr() + ": " + envelope.event())
        .runWith(Sink.foreach(System.out::println), system);
    // #currentEventsByPersistenceId
  }

  void exampleEventsBySlices() {
    // #currentEventsBySlices
    // Split the slices into 4 ranges
    int numberOfSliceRanges = 4;
    List<Pair<Integer, Integer>> sliceRanges = eventQueries.sliceRanges(numberOfSliceRanges);

    // Example of using the first slice range
    int minSlice = sliceRanges.get(0).first();
    int maxSlice = sliceRanges.get(0).second();
    String entityType = "MyEntity";
    Source<EventEnvelope<MyEvent>, NotUsed> source =
        eventQueries.currentEventsBySlices(entityType, minSlice, maxSlice, NoOffset.getInstance());
    source
        .map(
            envelope ->
                "event from persistenceId "
                    + envelope.persistenceId()
                    + " with seqNr "
                    + envelope.sequenceNr()
                    + ": "
                    + envelope.event())
        .runWith(Sink.foreach(System.out::println), system);
    // #currentEventsBySlices
  }

  void exampleStateBySlices() {
    // #currentChangesBySlices
    // Split the slices into 4 ranges
    int numberOfSliceRanges = 4;
    List<Pair<Integer, Integer>> sliceRanges = stateQueries.sliceRanges(numberOfSliceRanges);

    // Example of using the first slice range
    int minSlice = sliceRanges.get(0).first();
    int maxSlice = sliceRanges.get(0).second();
    String entityType = "MyEntity";
    Source<DurableStateChange<MyState>, NotUsed> source =
        stateQueries.currentChangesBySlices(entityType, minSlice, maxSlice, NoOffset.getInstance());
    source
        .collectType(UpdatedDurableState.class)
        .map(
            change ->
                "state change from persistenceId "
                    + change.persistenceId()
                    + " with revision "
                    + change.revision()
                    + ": "
                    + change.value())
        .runWith(Sink.foreach(System.out::println), system);
    // #currentChangesBySlices
  }
}
