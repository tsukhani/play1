package play.db.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import play.exceptions.JPAException;

/**
 * PF-231: {@link JPA#withoutTransaction} sets the thread's JPA contexts aside for a block, so that
 * the block runs with no transaction open, and puts them back when the block ends.
 */
public class JPAWithoutTransactionTest {

    private static final String NO_CONTEXT = "No active EntityManager for name [default], transaction not started?";

    /** What withTransaction puts on the thread, as a request or a job holds it. */
    private JPA.JPAContext runners;

    @BeforeEach
    public void aRunnerHoldsATransaction() {
        runners = new JPA.JPAContext();
        runners.readonly = false;
        JPA.currentEntityManager.get().put(JPA.DEFAULT, runners);
    }

    @AfterEach
    public void clearTheThread() {
        JPA.currentEntityManager.get().clear();
    }

    @Test
    public void theBlockSeesNoTransactionAndTheThreadsComesBackAfterIt() throws Throwable {
        Boolean insideTheBlock = JPA.withoutTransaction("hint", JPA::isInsideTransaction);

        assertThat(insideTheBlock).isFalse();
        assertThat(JPA.currentEntityManager.get()).containsExactly(entry(JPA.DEFAULT, runners));
    }

    @Test
    public void theThreadsTransactionComesBackWhenTheBlockFails() {
        assertThatThrownBy(() -> JPA.withoutTransaction("hint", () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(JPA.currentEntityManager.get()).containsExactly(entry(JPA.DEFAULT, runners));
    }

    @Test
    public void insideTheBlockTheEntityManagersFailureSaysWhatToDo() {
        assertThatThrownBy(() -> JPA.withoutTransaction("Open one.", JPA::em))
                .isInstanceOf(JPAException.class).hasMessage(NO_CONTEXT + " Open one.");
        assertThatThrownBy(() -> JPA.withoutTransaction("outer", () -> {
            JPA.withoutTransaction("inner", () -> null);
            return JPA.em();
        })).as("an inner block puts the outer one's hint back").hasMessage(NO_CONTEXT + " outer");

        JPA.currentEntityManager.get().clear();
        assertThatThrownBy(JPA::em).as("outside a block, the failure is as it was").hasMessage(NO_CONTEXT);
    }

    @Test
    public void anEntityManagerTheBlockLeavesOpenIsRolledBackAndClosed() throws Throwable {
        EntityManager leftOpen = mock(EntityManager.class);
        EntityTransaction itsTransaction = mock(EntityTransaction.class);
        when(leftOpen.isOpen()).thenReturn(true);
        when(leftOpen.getTransaction()).thenReturn(itsTransaction);
        when(itsTransaction.isActive()).thenReturn(true);

        // as JPA.startTx leaves it, with no closeTx after it
        JPA.withoutTransaction("hint", () -> {
            JPA.bindForCurrentThread(JPA.DEFAULT, leftOpen, false);
            return null;
        });

        verify(itsTransaction).rollback();
        verify(itsTransaction, never()).commit();
        verify(leftOpen).close();
        assertThat(JPA.currentEntityManager.get()).containsExactly(entry(JPA.DEFAULT, runners));
    }
}
