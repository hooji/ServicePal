package com.u1.servicepal.gui;

import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.Schedule;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.time.DayOfWeek;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * The "On a schedule" picker: a Repeat selector (Every N minutes / Daily / Weekly) plus the fields
 * for the chosen repeat. It only offers schedules that round-trip on <em>every</em> platform — the
 * minute intervals are all divisors of 60 (so OpenRC's cron fallback can express them) and the
 * daily/weekly calendar forms map cleanly to launchd / systemd / Task Scheduler / cron.
 *
 * <p>{@link #getSchedule()} is always non-null; {@link #setSchedule(Schedule)} pre-fills the picker
 * when editing an existing scheduled job.
 */
final class SchedulePanel extends JPanel {

	/** The repeat kinds the GUI offers. */
	private enum Repeat {
		EVERY_MINUTES("Every N minutes"),
		DAILY("Daily"),
		WEEKLY("Weekly");

		private final String label;

		Repeat(final String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** Minute intervals offered — all divide 60, so they are expressible as a cron step on OpenRC. */
	private static final Integer[] MINUTE_OPTIONS = {5, 10, 15, 20, 30};

	private final JComboBox<Repeat> repeat = new JComboBox<>(Repeat.values());
	private final JPanel cards = new JPanel(new CardLayout());

	private final JComboBox<Integer> minutes = new JComboBox<>(MINUTE_OPTIONS);
	private final JSpinner dailyHour = hourSpinner();
	private final JSpinner dailyMinute = minuteSpinner();
	private final JComboBox<DayOfWeek> weekday = new JComboBox<>(DayOfWeek.values());
	private final JSpinner weeklyHour = hourSpinner();
	private final JSpinner weeklyMinute = minuteSpinner();

	SchedulePanel() {
		super(new java.awt.GridBagLayout());

		final java.awt.GridBagConstraints l = new java.awt.GridBagConstraints();
		l.gridx = 0;
		l.gridy = 0;
		l.anchor = java.awt.GridBagConstraints.WEST;
		l.insets = new java.awt.Insets(0, 0, 0, 12);
		add(new JLabel("Repeat"), l);

		final java.awt.GridBagConstraints r = new java.awt.GridBagConstraints();
		r.gridx = 1;
		r.gridy = 0;
		r.anchor = java.awt.GridBagConstraints.WEST;
		add(repeat, r);

		final java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
		c.gridx = 1;
		c.gridy = 1;
		c.anchor = java.awt.GridBagConstraints.WEST;
		c.insets = new java.awt.Insets(10, 0, 0, 0);
		cards.add(everyCard(), Repeat.EVERY_MINUTES.name());
		cards.add(dailyCard(), Repeat.DAILY.name());
		cards.add(weeklyCard(), Repeat.WEEKLY.name());
		add(cards, c);

		weekday.setRenderer(new DayRenderer());
		minutes.setSelectedItem(15);
		dailyHour.setValue(9);
		weeklyHour.setValue(9);
		repeat.addActionListener(e -> showCard());
		repeat.setSelectedItem(Repeat.DAILY);
		showCard();
	}

	/** The chosen schedule (never {@code null}). */
	Schedule getSchedule() {
		return switch ((Repeat) repeat.getSelectedItem()) {
			case EVERY_MINUTES -> Schedule.everyMinutes((Integer) minutes.getSelectedItem());
			case DAILY -> Schedule.dailyAt(intValue(dailyHour), intValue(dailyMinute));
			case WEEKLY -> Schedule.weeklyAt((DayOfWeek) weekday.getSelectedItem(),
					intValue(weeklyHour), intValue(weeklyMinute));
		};
	}

	/** Pre-fill the picker from an existing schedule (best-effort for shapes the GUI can't author). */
	void setSchedule(final Schedule schedule) {
		if (schedule instanceof IntervalSchedule interval) {
			repeat.setSelectedItem(Repeat.EVERY_MINUTES);
			selectMinutes((int) interval.period().toMinutes());
		} else if (schedule instanceof CalendarSchedule calendar) {
			final CalendarSpec s = calendar.spec();
			if (s.dayOfWeek() != null) {
				repeat.setSelectedItem(Repeat.WEEKLY);
				weekday.setSelectedItem(dayOf(s.dayOfWeek()));
				weeklyHour.setValue(s.hour() == null ? 0 : s.hour());
				weeklyMinute.setValue(s.minute() == null ? 0 : s.minute());
			} else {
				repeat.setSelectedItem(Repeat.DAILY);
				dailyHour.setValue(s.hour() == null ? 0 : s.hour());
				dailyMinute.setValue(s.minute() == null ? 0 : s.minute());
			}
		}
		showCard();
	}

	private void showCard() {
		((CardLayout) cards.getLayout()).show(cards, ((Repeat) repeat.getSelectedItem()).name());
	}

	private JPanel everyCard() {
		final JPanel p = row();
		p.add(minutes);
		p.add(new JLabel("minutes"));
		return p;
	}

	private JPanel dailyCard() {
		final JPanel p = row();
		p.add(new JLabel("at"));
		p.add(dailyHour);
		p.add(new JLabel(":"));
		p.add(dailyMinute);
		return p;
	}

	private JPanel weeklyCard() {
		final JPanel p = row();
		p.add(new JLabel("on"));
		p.add(weekday);
		p.add(new JLabel("at"));
		p.add(weeklyHour);
		p.add(new JLabel(":"));
		p.add(weeklyMinute);
		return p;
	}

	private void selectMinutes(final int value) {
		for (final Integer option : MINUTE_OPTIONS) {
			if (option == value) {
				minutes.setSelectedItem(option);
				return;
			}
		}
		minutes.setSelectedItem(15);   // an interval the GUI can't author (e.g. hours): a safe default
	}

	private static DayOfWeek dayOf(final int cronDayOfWeek) {
		// Stored as DayOfWeek.getValue() (1-7, SUN=7); cron 0 also means Sunday.
		return cronDayOfWeek == 0 || cronDayOfWeek == 7 ? DayOfWeek.SUNDAY : DayOfWeek.of(cronDayOfWeek);
	}

	private static int intValue(final JSpinner spinner) {
		return ((Number) spinner.getValue()).intValue();
	}

	private static JPanel row() {
		final JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		return p;
	}

	private static JSpinner hourSpinner() {
		final JSpinner spinner = new JSpinner(new SpinnerNumberModel(0, 0, 23, 1));
		spinner.setEditor(new JSpinner.NumberEditor(spinner, "00"));
		return spinner;
	}

	private static JSpinner minuteSpinner() {
		final JSpinner spinner = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
		spinner.setEditor(new JSpinner.NumberEditor(spinner, "00"));
		return spinner;
	}

	/** Renders a {@link DayOfWeek} with its full local name (e.g. "Monday"). */
	private static final class DayRenderer extends DefaultListCellRenderer {
		@Override
		public Component getListCellRendererComponent(final JList<?> list, final Object value,
				final int index, final boolean isSelected, final boolean cellHasFocus) {
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			if (value instanceof DayOfWeek day) {
				setText(day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()));
			}
			return this;
		}
	}
}
