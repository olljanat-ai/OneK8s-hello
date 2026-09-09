package io.onek8s.dbjava.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One page view — the same row the .NET application writes, in the same table.
 *
 * <p>This is the one place where the two applications are <em>not</em> mirror
 * images. In db-hello the entity <em>is</em> the schema: change the class, add
 * an EF Core migration, and the database follows. Here the schema is already
 * decided, so this class is a mapping of a table it does not own — no
 * migrations, no {@code ddl-auto}, and every column named explicitly so a
 * naming strategy cannot quietly invent one. If db-hello's model changes, this
 * class follows it; it never leads.
 *
 * <p>The consequence is worth stating: both applications write to
 * {@code visits}, so each one's page shows the other's rows. The {@code pod}
 * column is what tells them apart.
 */
@Entity
@Table(name = "visits")
public class Visit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Stamped by the database (the column's {@code SYSUTCDATETIME()} default)
     * rather than by the pod, so the times in the table are one clock's and not
     * one per replica. Never written by this application, and read back after
     * the insert — which is what {@code @Generated} arranges.
     *
     * <p>{@code LocalDateTime} rather than an instant with an offset, because
     * the column is {@code datetime2(0)}: UTC by convention, and carrying no
     * zone of its own to convert from.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "visited_at", insertable = false, updatable = false)
    private LocalDateTime visitedAt;

    @Column(name = "pod", length = 128, nullable = false)
    private String pod;

    @Column(name = "cloud", length = 32, nullable = false)
    private String cloud;

    protected Visit() {
        // for Hibernate
    }

    public Visit(String pod, String cloud) {
        this.pod = pod;
        this.cloud = cloud;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getVisitedAt() {
        return visitedAt;
    }

    public String getPod() {
        return pod;
    }

    public String getCloud() {
        return cloud;
    }
}
