package com.u1.servicepal.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The grouping logic: managed jobs and other jobs become two header-separated sections. */
class JobTableModelTest {

	private static Job job(final String id, final boolean managed) {
		return job(id, managed, false);
	}

	private static Job job(final String id, final boolean managed, final boolean adopted) {
		final ServiceSpec spec = ServiceSpec.builder().id(id).command("/bin/x").build();
		final ServiceStatus status = new ServiceStatus(id, Installation.PER_USER, true, true,
				managed, adopted, RunState.RUNNING, 1, null, null);
		return new Job(spec, status);
	}

	@Test
	void splitsIntoTwoHeaderSeparatedSections() {
		final JobTableModel model = new JobTableModel();
		model.setJobs(List.of(job("a", true), job("b", false), job("c", true)));

		// rows: [Header "Created…" 2] a c [Header "Other…" 1] b
		assertEquals(5, model.getRowCount());
		assertTrue(model.isHeader(0));
		assertTrue(model.getValueAt(0, 0) instanceof JobTableModel.Header);
		assertEquals(2, ((JobTableModel.Header) model.getValueAt(0, 0)).count());
		assertEquals("", model.getValueAt(0, 1));   // header has no state

		assertFalse(model.isHeader(1));
		assertEquals("a", model.jobAt(1).id());
		assertEquals("c", model.jobAt(2).id());
		assertTrue(model.isHeader(3));
		assertEquals(1, ((JobTableModel.Header) model.getValueAt(3, 0)).count());
		assertEquals("b", model.jobAt(4).id());
	}

	@Test
	void headerRowsHaveNoJob() {
		final JobTableModel model = new JobTableModel();
		model.setJobs(List.of(job("a", true), job("b", false)));
		assertNull(model.jobAt(0), "row 0 is the managed header");
		assertNull(model.jobAt(2), "the others header has no job");
	}

	@Test
	void firstJobRowSkipsTheLeadingHeader() {
		final JobTableModel model = new JobTableModel();
		model.setJobs(List.of(job("a", true)));
		assertEquals(1, model.firstJobRow());
		assertEquals(2, model.getRowCount());
	}

	@Test
	void onlyOtherJobsStillGetsItsOwnSection() {
		final JobTableModel model = new JobTableModel();
		model.setJobs(List.of(job("x", false), job("y", false)));
		// No managed section; one "Other" header then the two jobs.
		assertEquals(3, model.getRowCount());
		assertTrue(model.isHeader(0));
		assertEquals(1, model.firstJobRow());
		assertEquals(1, model.indexOfId("x"));
		assertEquals(2, model.indexOfId("y"));
	}

	@Test
	void adoptedJobsGetTheirOwnMiddleSection() {
		final JobTableModel model = new JobTableModel();
		model.setJobs(List.of(job("c", true, false), job("ad", true, true), job("o", false)));
		// Three sections: Created, Adopted, Other — each with a header then its job.
		assertEquals(6, model.getRowCount());
		assertTrue(model.isHeader(0));
		assertEquals("c", model.jobAt(1).id());
		assertEquals("Adopted by ServicePal", ((JobTableModel.Header) model.getValueAt(2, 0)).title());
		assertEquals("ad", model.jobAt(3).id());
		assertEquals("Other background jobs", ((JobTableModel.Header) model.getValueAt(4, 0)).title());
		assertEquals("o", model.jobAt(5).id());
	}

	@Test
	void emptyHasNoRows() {
		final JobTableModel model = new JobTableModel();
		model.setJobs(List.of());
		assertEquals(0, model.getRowCount());
		assertEquals(-1, model.firstJobRow());
		assertEquals(-1, model.indexOfId("nope"));
	}
}
