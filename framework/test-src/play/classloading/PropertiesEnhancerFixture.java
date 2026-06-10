package play.classloading;

/**
 * Fixture for {@link play.classloading.PropertiesEnhancerTest}.
 *
 * Public, non-static, lower-cased fields are valid JavaBean properties, so
 * PropertiesEnhancer should generate getName/setName + getAge/setAge for them and
 * rewrite the direct field accesses in {@link #readNameDirect()} /
 * {@link #writeAgeDirect(int)} to go through PropertiesEnhancer.FieldAccessor.
 *
 * The class is compiled by the normal test build (so we read its real .class bytes
 * off the classpath at test time and feed them, UN-enhanced, into the enhancer).
 */
public class PropertiesEnhancerFixture {

    public String name;
    public int age;

    // Direct field reads/writes — the enhancer must rewrite these call sites.
    public String readNameDirect() {
        return this.name;
    }

    public void writeAgeDirect(int newAge) {
        this.age = newAge;
    }

    public int readAgeDirect() {
        return this.age;
    }
}
