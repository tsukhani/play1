package models;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import play.db.jpa.Model;

/**
 * PF-190 fixture: the element of {@link CachedShelf#books}, and the target of a cacheable query
 * in {@code integration.FixturesSecondLevelCacheTest}.
 */
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class CachedBook extends Model {
    public String title;

    @ManyToOne
    public CachedShelf shelf;
}
