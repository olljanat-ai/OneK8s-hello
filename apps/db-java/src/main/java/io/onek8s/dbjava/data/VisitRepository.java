package io.onek8s.dbjava.data;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The queries, as method names — the counterpart to the LINQ in db-hello's
 * Program.cs, and the whole of the data access this application does through
 * the model.
 */
public interface VisitRepository extends JpaRepository<Visit, Long> {

    /** The last ten page views, newest first, by either application. */
    List<Visit> findTop10ByOrderByIdDesc();
}
