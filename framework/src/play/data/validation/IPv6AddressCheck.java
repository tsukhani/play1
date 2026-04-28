package play.data.validation;

import java.net.Inet6Address;
import java.net.InetAddress;

import net.sf.oval.Validator;
import net.sf.oval.configuration.annotation.AbstractAnnotationCheck;
import net.sf.oval.context.OValContext;
import net.sf.oval.exception.OValException;

public class IPv6AddressCheck extends AbstractAnnotationCheck<IPv6Address> {

    static final String mes = "validation.ipv6";

    @Override
    public void configure(IPv6Address phone) {
        setMessage(phone.message());
    }

    @Override
    public boolean isSatisfied(Object validatedObject, Object value, OValContext context, Validator validator)
    throws OValException {
        if (value == null || value.toString().length() == 0) {
            return true;
        }
        // Audit M20: literal-only parse via InetAddress.ofLiteral (Java 22+).
        // The previous InetAddress.getByName performed a DNS lookup when the
        // input wasn't a literal — letting hostnames pass the @IPv6Address
        // check (a hostname with an AAAA record would resolve to Inet6Address)
        // and creating a DNS oracle / latency vector for every validation call.
        // ofLiteral throws IllegalArgumentException on non-literal input —
        // exactly the desired semantics — and never queries the network.
        try {
            InetAddress addr = InetAddress.ofLiteral(value.toString());
            return addr instanceof Inet6Address;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

}
