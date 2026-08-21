// core_engine/src/calendar/mod.rs
//
// Release calendar — point K.
//
// The evaluation was right that there is nothing to import here: Stremio's `calendar` is about
// episodes airing, and we have albums coming out. It was also right that "a calendar of releases by
// the artists you follow is a natural evolution" of the New Releases screen we already have.
//
// What belongs in the core is not the screen. It is the one part that is genuinely easy to get
// wrong and impossible to notice: **Spotify release dates come in three precisions.**
//
//   * `"2024-03-15"` — day
//   * `"2024-03"`    — month, for a lot of older catalogue
//   * `"2024"`       — year, for most of anything before the eighties
//
// Parsing all three as a day is what a `substring(0, 10)` does, and it produces an album released
// "in 1975" sitting on the 1st of January 1975 next to the ones that really came out that day. So
// the precision travels with the date and a comparison that does not know both is not offered.
//
// There is no calendar arithmetic library here and there does not need to be: bucketing by "this
// week / this month / this year / older" only needs day counts, and a proleptic Gregorian day number
// is thirty lines that are testable rather than a dependency that is not.

use serde::{Deserialize, Serialize};

/// How much of a date is actually known.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Precision {
    Year,
    Month,
    Day,
}

/// A release date as Spotify actually gives it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct ReleaseDate {
    pub year: i32,
    /// 1-12. When the precision is `Year` this is 1, and means "unknown", not "January".
    pub month: u32,
    /// 1-31. When the precision is not `Day` this is 1 and means "unknown".
    pub day: u32,
    pub precision: Precision,
}

impl ReleaseDate {
    /// Parses `"2024-03-15"`, `"2024-03"` or `"2024"`.
    ///
    /// `precision_hint` is Spotify's own `release_date_precision`, used when it is present and
    /// believed over the shape of the string — a day-precision field that happens to read `"2024"`
    /// is a Spotify bug we should not amplify, but a `"2024-03-15"` with a `year` hint is
    /// deliberate and means the day is padding.
    pub fn parse(text: &str, precision_hint: Option<&str>) -> Option<ReleaseDate> {
        let mut parts = text.trim().split('-');
        let year: i32 = parts.next()?.trim().parse().ok()?;
        if !(1000..=3000).contains(&year) {
            return None;
        }
        let month: Option<u32> = parts.next().and_then(|m| m.trim().parse().ok());
        let day: Option<u32> = parts.next().and_then(|d| d.trim().parse().ok());

        let from_shape = match (month, day) {
            (Some(_), Some(_)) => Precision::Day,
            (Some(_), None) => Precision::Month,
            _ => Precision::Year,
        };
        let precision = match precision_hint.map(str::trim) {
            Some("day") => Precision::Day,
            Some("month") => Precision::Month,
            Some("year") => Precision::Year,
            // No hint, or one nobody recognises: the string is the only evidence there is.
            _ => from_shape,
        };
        // A precision cannot be finer than what the string actually carries, whatever the hint says.
        let precision = precision.min(from_shape);

        let month = month.filter(|m| (1..=12).contains(m)).unwrap_or(1);
        let day = day.filter(|d| (1..=31).contains(d)).unwrap_or(1);
        Some(ReleaseDate {
            year,
            month: if precision >= Precision::Month { month } else { 1 },
            day: if precision == Precision::Day { day } else { 1 },
            precision,
        })
    }

    /// Days since 1970-01-01, proleptic Gregorian. Negative before it.
    ///
    /// For a `Year` or `Month` precision this is the *first* day of the period, which is what makes
    /// "is it older than a week" answerable at all — the alternative is refusing to sort half the
    /// catalogue.
    pub fn day_number(&self) -> i64 {
        days_from_civil(self.year as i64, self.month, self.day)
    }
}

/// Where a release falls relative to today.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Bucket {
    /// Dated after today. Spotify does list pre-releases, and burying them among last month's is
    /// the one thing a calendar exists to avoid.
    Upcoming,
    Today,
    ThisWeek,
    ThisMonth,
    ThisYear,
    Older,
    /// No usable date at all.
    Unknown,
}

/// Buckets `date` against `today`, both as day numbers.
///
/// "This week" is the last seven days and not "since Monday": a calendar that empties itself every
/// Monday morning is a calendar people stop opening on Mondays.
pub fn bucket(date: Option<ReleaseDate>, today: ReleaseDate) -> Bucket {
    let Some(date) = date else {
        return Bucket::Unknown;
    };
    let d = date.day_number();
    let now = today.day_number();
    if d > now {
        return Bucket::Upcoming;
    }
    // A year-precision release from the current year is "this year", never "today", even though its
    // day number is the 1st of January. Precision is what stops that lie.
    match date.precision {
        Precision::Year => {
            return if date.year == today.year {
                Bucket::ThisYear
            } else {
                Bucket::Older
            }
        }
        Precision::Month => {
            return if date.year == today.year && date.month == today.month {
                Bucket::ThisMonth
            } else if date.year == today.year {
                Bucket::ThisYear
            } else {
                Bucket::Older
            }
        }
        Precision::Day => {}
    }
    let age = now - d;
    if age == 0 {
        Bucket::Today
    } else if age < 7 {
        Bucket::ThisWeek
    } else if date.year == today.year && date.month == today.month {
        Bucket::ThisMonth
    } else if date.year == today.year {
        Bucket::ThisYear
    } else {
        Bucket::Older
    }
}

/// One album, as much of it as a calendar needs.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entry {
    pub id: String,
    #[serde(default)]
    pub release_date: String,
    #[serde(default)]
    pub release_date_precision: String,
}

/// An album placed on the calendar.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Placed {
    pub id: String,
    pub bucket: Bucket,
    /// Day number, for sorting. `None` when the date could not be read at all.
    pub day: Option<i64>,
}

/// Places every entry and sorts them newest first, with undated ones last.
///
/// Sorting here rather than on the other side of JNI is deliberate: "newest first" over three
/// precisions is exactly the comparison this module exists to own.
pub fn arrange(entries: &[Entry], now_ms: i64) -> Vec<Placed> {
    let today = today_from_ms(now_ms);
    let mut placed: Vec<Placed> = entries
        .iter()
        .map(|e| {
            let hint = if e.release_date_precision.is_empty() {
                None
            } else {
                Some(e.release_date_precision.as_str())
            };
            let parsed = ReleaseDate::parse(&e.release_date, hint);
            Placed {
                id: e.id.clone(),
                bucket: bucket(parsed, today),
                day: parsed.map(|d| d.day_number()),
            }
        })
        .collect();
    placed.sort_by(|a, b| match (a.day, b.day) {
        (Some(x), Some(y)) => y.cmp(&x),
        // Undated last, and stable among themselves.
        (Some(_), None) => std::cmp::Ordering::Less,
        (None, Some(_)) => std::cmp::Ordering::Greater,
        (None, None) => std::cmp::Ordering::Equal,
    });
    placed
}

/// Today, from a millisecond clock. UTC, which is a deliberate simplification: a release date has no
/// time zone to begin with, so pretending to place it in the user's is precision that is not there.
pub fn today_from_ms(now_ms: i64) -> ReleaseDate {
    let days = now_ms.div_euclid(86_400_000);
    let (year, month, day) = civil_from_days(days);
    ReleaseDate {
        year: year as i32,
        month,
        day,
        precision: Precision::Day,
    }
}

// --------------------------------------------------------------------------------------------
// Days ↔ civil dates. Howard Hinnant's algorithms, which are the ones every date library uses and
// are short enough to carry rather than depend on. Valid for the proleptic Gregorian calendar.
// --------------------------------------------------------------------------------------------

fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let m = m as i64;
    let d = d as i64;
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400; // [0, 399]
    let mp = (m + 9) % 12; // March = 0
    let doy = (153 * mp + 2) / 5 + d - 1; // [0, 365]
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy; // [0, 146096]
    era * 146_097 + doe - 719_468
}

fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365; // [0, 399]
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11], March = 0
    let d = doy - (153 * mp + 2) / 5 + 1; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 }; // [1, 12]
    (if m <= 2 { y + 1 } else { y }, m as u32, d as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn day(y: i32, m: u32, d: u32) -> ReleaseDate {
        ReleaseDate {
            year: y,
            month: m,
            day: d,
            precision: Precision::Day,
        }
    }

    #[test]
    fn all_three_spotify_precisions_parse() {
        assert_eq!(
            ReleaseDate::parse("2024-03-15", Some("day")).unwrap(),
            day(2024, 3, 15)
        );
        let m = ReleaseDate::parse("2024-03", Some("month")).unwrap();
        assert_eq!((m.year, m.month, m.precision), (2024, 3, Precision::Month));
        let y = ReleaseDate::parse("1975", Some("year")).unwrap();
        assert_eq!((y.year, y.precision), (1975, Precision::Year));
    }

    #[test]
    fn a_year_precision_release_is_not_the_first_of_january() {
        // The bug this module exists to prevent: an album "from 1975" must not sort or bucket as
        // though it came out on new year's day.
        let today = day(1975, 6, 1);
        let y = ReleaseDate::parse("1975", Some("year"));
        assert_eq!(bucket(y, today), Bucket::ThisYear);
        assert_ne!(bucket(y, today), Bucket::Today);
    }

    #[test]
    fn the_hint_cannot_claim_more_precision_than_the_string_has() {
        // Spotify occasionally sends `precision: "day"` with a bare year. Believing it would put
        // the album on the 1st of January with a straight face.
        let d = ReleaseDate::parse("1975", Some("day")).unwrap();
        assert_eq!(d.precision, Precision::Year);
    }

    #[test]
    fn a_finer_string_with_a_coarser_hint_follows_the_hint() {
        let d = ReleaseDate::parse("1975-01-01", Some("year")).unwrap();
        assert_eq!(d.precision, Precision::Year);
        assert_eq!(d.month, 1);
    }

    #[test]
    fn rubbish_is_none_rather_than_a_default_date() {
        assert!(ReleaseDate::parse("", None).is_none());
        assert!(ReleaseDate::parse("soon", None).is_none());
        assert!(ReleaseDate::parse("0042-01-01", None).is_none());
    }

    #[test]
    fn buckets_walk_from_upcoming_to_older() {
        let today = day(2024, 6, 15);
        assert_eq!(bucket(Some(day(2024, 7, 1)), today), Bucket::Upcoming);
        assert_eq!(bucket(Some(day(2024, 6, 15)), today), Bucket::Today);
        assert_eq!(bucket(Some(day(2024, 6, 10)), today), Bucket::ThisWeek);
        assert_eq!(bucket(Some(day(2024, 6, 2)), today), Bucket::ThisMonth);
        assert_eq!(bucket(Some(day(2024, 2, 2)), today), Bucket::ThisYear);
        assert_eq!(bucket(Some(day(2023, 12, 31)), today), Bucket::Older);
        assert_eq!(bucket(None, today), Bucket::Unknown);
    }

    #[test]
    fn this_week_is_the_last_seven_days_not_since_monday() {
        // A calendar that empties every Monday is one people stop opening on Mondays.
        let monday = day(2024, 6, 17);
        let previous_saturday = day(2024, 6, 15);
        assert_eq!(bucket(Some(previous_saturday), monday), Bucket::ThisWeek);
    }

    #[test]
    fn day_numbers_round_trip_across_leap_years_and_centuries() {
        for (y, m, d) in [
            (1970, 1, 1),
            (2000, 2, 29),
            (1900, 3, 1),
            (2024, 2, 29),
            (2100, 3, 1),
            (1969, 12, 31),
        ] {
            let n = days_from_civil(y, m, d);
            assert_eq!(civil_from_days(n), (y, m, d), "{y}-{m}-{d}");
        }
        assert_eq!(days_from_civil(1970, 1, 1), 0);
    }

    #[test]
    fn arranging_puts_the_newest_first_and_the_undated_last() {
        let entries = vec![
            Entry {
                id: "old".into(),
                release_date: "1975".into(),
                release_date_precision: "year".into(),
            },
            Entry {
                id: "nodate".into(),
                release_date: "".into(),
                release_date_precision: "".into(),
            },
            Entry {
                id: "new".into(),
                release_date: "2024-06-14".into(),
                release_date_precision: "day".into(),
            },
            Entry {
                id: "mid".into(),
                release_date: "2024-01".into(),
                release_date_precision: "month".into(),
            },
        ];
        // 2024-06-15 as a millisecond clock.
        let now_ms = days_from_civil(2024, 6, 15) * 86_400_000;
        let placed = arrange(&entries, now_ms);
        let order: Vec<&str> = placed.iter().map(|p| p.id.as_str()).collect();
        assert_eq!(order, vec!["new", "mid", "old", "nodate"]);
        assert_eq!(placed[0].bucket, Bucket::ThisWeek);
        assert_eq!(placed[3].bucket, Bucket::Unknown);
    }

    #[test]
    fn today_reads_back_from_a_millisecond_clock() {
        let now_ms = days_from_civil(2024, 6, 15) * 86_400_000 + 13 * 3_600_000;
        let today = today_from_ms(now_ms);
        assert_eq!((today.year, today.month, today.day), (2024, 6, 15));
    }
}
