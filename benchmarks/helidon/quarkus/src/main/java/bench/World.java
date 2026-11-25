package bench;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "world")
public class World {
    @Id
    public Integer id;
    public Integer randomNumber;
}
