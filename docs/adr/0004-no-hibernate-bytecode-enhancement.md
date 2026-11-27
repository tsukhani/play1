# 0004. No Hibernate bytecode enhancement; declared-lazy attributes are named instead

- **Status:** Accepted
- **Date:** 2026-11-27
- **Ticket:** PF-56

## Context

Hibernate 7.4 can enhance entity classes, rewriting their bytecode so that attributes load lazily, the entity
tracks its own changes, and the persistence context needs no map to find it. Play's entities are never enhanced:

- Hibernate makes its load-time transformer only when `PersistenceUnitInfo.getNewTempClassLoader()` returns a
  loader. `PersistenceUnitInfoImpl` returns none, so `addTransformer` is never called.
- Even with a transformer there would be nothing to apply it to: Play has defined every entity class before
  `JPAPlugin` builds the factory.
- Hibernate's Gradle and Maven enhancement plugins enhance the classes the build tool compiles. Play compiles and
  defines the application's classes itself, so those plugins change nothing Play loads.

Without enhancement, `@Basic(fetch = LAZY)` (including large text and `@Lob` columns and `@LazyGroup`) and the
inverse side of a lazy `@OneToOne` load eagerly. Hibernate logs nothing about it, even at DEBUG. Lazy `@ManyToOne`,
the owning side of `@OneToOne` and collections stay lazy through proxies.

The question was whether to do what Quarkus does. Quarkus 3.40.1 enhances every entity in its own build step,
lazy loading and inline dirty tracking included. Helidon 4.5.7 drops the transformer as Play does, and tells
applications to add Hibernate's Maven plugin to their build. Play's equivalent of the Quarkus step would be a link
in its own enhancer chain, which serves DEV, test and precompile and feeds the bytecode cache.

### Measurement

JMH 1.37 on 24 CPUs of a Linux machine, in two alternated rounds:

- the fork's libraries: Hibernate 7.4.17, ByteBuddy 1.18.17, HikariCP 7.1.0, and H2 2.5.252 in memory;
- a `PersistenceUnitInfo` built as Play builds it, with 41 entities that use field access, getters and setters;
- three variants: unenhanced (as now), enhanced for lazy loading only, and enhanced as Quarkus does.

| Measure | Unenhanced | Lazy loading only | As Quarkus |
|---|---|---|---|
| `find` by id, fresh EntityManager | 2.51 µs, 6.8 KB | −2.4%, −5% allocated | −1.0%, −5% allocated |
| load 1,000 entities, flush, no change | 1,029 µs | −4.5% | −8.8% |
| load 1,000 entities, change 10, flush | 1,078 µs | −4.3% | −7.7% |
| factory build, cold JVM | 1,111 ms | −5.6% | −5.1% |
| heap per managed entity | 708 B | +5.5% | +6.6% |
| entity class files | 115 KB | 3.0× | 3.7× |
| enhancement time | none | 2.1 ms per class, 0.4 s first pass | 2.3 ms per class |

A whole plain-text request costs about 41 µs of server CPU, so the gain on a `find` is about 0.15% of it. The
lazy attribute's own benefit, a column never fetched, does not show against H2 in memory, which hands back a
64 KB value without copying it. Against a database across a network it would. The variant with lazy loading but
without inline dirty tracking relies on `hibernate.enhancer.enableDirtyTracking=false`, which Hibernate 7.4
deprecates for removal.

### What turning it on would change

These were checked against Hibernate 7.4 on the fork's jars:

- **Lazy attributes would become lazy for real.** An application that declares them has always had them loaded.
  `renderJSON` reads fields by reflection, and would leave out an attribute that is not loaded, without a word:
  `{"title":"t","body":"…","id":1}` became `{"title":"t","id":1}`. Code that reads one after its EntityManager
  closes, a cached entity for one, would get a `LazyInitializationException`.
- **Inline dirty tracking would miss a write that bypasses the enhanced field.** Play's binder writes a property
  that has no setter by reflection (`BeanWrapper.Property.setValue`), and a missed write is a lost update. One case
  was not settled: a value changed in place, such as a `Date`, an array or a JSON map.
- **Not a blocker:** entities that extend `play.db.jpa.Model`, which stays unenhanced in the framework jar. With
  the application's classes enhanced, the hierarchy boots, defers its lazy column and reads ids of uninitialized
  references.

## Decision

Play does not enhance entities. Instead, a start names the attributes that are declared lazy but load eagerly,
in one WARN per persistence unit (`play.db.jpa.LazyAttributes`). `addTransformer` reports a transformer instead of
dropping it without a word. The manual's JPA chapter, under "Lazy attributes", says what does not apply and how to
keep a large column out of the queries that do not need it.

The gains measured are small next to what turning enhancement on would change for existing applications, and next
to the work of fitting it into Play's enhancer chain, its bytecode cache and DEV reloads.

## Consequences

- Applications behave as they always have. The silent part is gone: those that declare lazy attributes are told,
  and those that declare none, such as the largest application built on the fork, with 44 entities, see nothing.
- An attribute that must stay out of most queries has to be moved to an entity of its own, reached through a
  `@ManyToOne` or the owning side of a `@OneToOne`, or left out by a query that selects only what it needs.
- Dirty checking stays a comparison with the state each entity was loaded with, and lazy to-one references stay
  ByteBuddy proxies.

## Re-evaluation triggers

The question stays closed unless one of these changes.

1. **An application needs a lazy column it cannot move out of its entity**, with a measured cost of loading it
   eagerly against its real database.
2. **Hibernate makes enhancement a requirement**, or a feature an application needs only works on enhanced entities.
3. **Flushing large persistence contexts dominates a real application's CPU**, where the Quarkus variant measured
   8 to 9 percent.
4. **A Jakarta Persistence release makes the container responsible for enhancement.**

If it is reopened, do it as Quarkus does, in Play's enhancer chain and off for an application that opts out. Before
it is on by default, settle the two open checks above (writes without a setter, values changed in place), and run
an application's full suite with it on.
