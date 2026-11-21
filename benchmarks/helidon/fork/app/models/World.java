package models;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import play.db.jpa.GenericModel;

@Entity
@Table(name = "world")
public class World extends GenericModel {
    @Id
    public Integer id;
    public Integer randomNumber;
}
