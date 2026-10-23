package models;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import play.db.jpa.Model;

/**
 * PF-190 fixture: an entity and a collection held in Hibernate's second-level cache, for
 * {@code integration.FixturesSecondLevelCacheTest}.
 */
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class CachedShelf extends Model {
    public String name;

    @OneToMany(mappedBy = "shelf")
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    public List<CachedBook> books = new ArrayList<>();
}
