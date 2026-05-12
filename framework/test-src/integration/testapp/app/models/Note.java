package models;

import jakarta.persistence.Entity;

import play.db.jpa.Model;

/**
 * PF-108 fixture: minimal JPA entity for the lazy-tx pool regression test.
 * The {@code /count} endpoint runs {@code SELECT COUNT(*) FROM Note}, which
 * is the cheapest way to force {@link play.db.jpa.JPA#em()} to materialize a
 * connection without populating any rows.
 */
@Entity
public class Note extends Model {
    public String text;
}
