// core_engine/src/player/mod.rs
//
// The playback model: a queue, where you are in it, and what each action does to both.
//
// Point H of the stremio-core evaluation, adopted **selectively** — which is what that evaluation
// concluded. Not the whole Elm runtime: that is a rewrite, it fights how Compose wants state, and
// Stremio pays for it with a bridge layer and constant re-serialisation. What is worth taking is the
// shape: state is data, an action is data, and **the consequences are data too** ([`Effect`]).
//
// What lives here is the part that is genuinely hard and genuinely portable:
//
//   * what "next" means when repeat is on, and how that differs depending on whether a person
//     pressed the button or the track simply ended;
//   * what "previous" means when you are twenty seconds into a song;
//   * where the queue index ends up in each case.
//
// Every one of those is arithmetic over a list, none of it needs a platform, and all of it is the
// kind of logic that is wrong in a way nobody notices for a release. `AudioPlayerService` keeps
// owning ExoPlayer and the observable Compose state; this owns the decisions.
//
// Deliberately **not** here: track metadata. The queue is a list of `TrackId` strings, because that
// is the only part Windows and iOS would agree on anyway.

pub mod session;

use serde::{Deserialize, Serialize};

/// Past this point into a track, "previous" restarts it instead of going back one.
///
/// The same ten seconds `AudioPlayerService` used, moved here so the rule has one home.
pub const PREVIOUS_RESTART_THRESHOLD_MS: u64 = 10_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum Repeat {
    #[default]
    Off,
    /// Wrap around at the end of the queue.
    All,
    /// The current track plays again when it ends — but a person pressing "next" still moves on.
    One,
}

/// Where playback is. Everything a session needs to be resumed and nothing else.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct PlayerState {
    /// Track ids in play order. Already shuffled if shuffle is on — the shuffle is *in* the order,
    /// not applied on top of it, which is what makes "where am I" a single number.
    pub queue: Vec<String>,
    pub index: usize,
    pub position_ms: u64,
    #[serde(default)]
    pub repeat: Repeat,
    #[serde(default)]
    pub shuffle: bool,
}

impl PlayerState {
    pub fn current(&self) -> Option<&str> {
        self.queue.get(self.index).map(String::as_str)
    }

    pub fn is_empty(&self) -> bool {
        self.queue.is_empty()
    }
}

/// Something that happened, or that someone asked for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    SetQueue { tracks: Vec<String>, start: usize },
    /// Someone pressed the button. Distinct from [`Action::TrackEnded`] on purpose.
    Next,
    Previous,
    Seek(u64),
    /// Playback progressed. The only action that arrives without anyone doing anything.
    Tick(u64),
    /// The current track finished on its own.
    TrackEnded,
    SetRepeat(Repeat),
    /// The order is supplied rather than computed: shuffling needs a random source, and this module
    /// deliberately has none — a pure reducer that reaches for entropy is neither pure nor testable.
    SetShuffle { on: bool, order: Vec<String> },
    Clear,
}

/// What the platform has to actually do about it.
///
/// The point of making these data: a race between two of them is visible in a test rather than in a
/// bug report. The `preservedPosition` bug of 2.11.8 was two effects arriving in the wrong order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Effect {
    /// Load and play this track from `position_ms`.
    Play { track: String, position_ms: u64 },
    /// Seek within what is already playing — no reload.
    SeekTo(u64),
    /// Nothing more to play.
    Stop,
    /// The state changed in a way worth remembering across a restart.
    Persist,
}

/// Applies `action` to `state` and says what follows from it.
///
/// Total: every action has an answer for every state, including an empty queue. That is the property
/// worth having — the caller never has to ask "can I press next right now".
pub fn reduce(state: &mut PlayerState, action: Action) -> Vec<Effect> {
    match action {
        Action::SetQueue { tracks, start } => {
            if tracks.is_empty() {
                *state = PlayerState {
                    repeat: state.repeat,
                    shuffle: state.shuffle,
                    ..PlayerState::default()
                };
                return vec![Effect::Stop, Effect::Persist];
            }
            let index = start.min(tracks.len() - 1);
            let track = tracks[index].clone();
            state.queue = tracks;
            state.index = index;
            state.position_ms = 0;
            vec![
                Effect::Play {
                    track,
                    position_ms: 0,
                },
                Effect::Persist,
            ]
        }

        // A person pressing "next" always moves on, even with repeat-one. Repeat-one means "play
        // this song again when it ends", not "trap me here" — the distinction between this and
        // `TrackEnded` is the whole reason they are separate actions.
        Action::Next => advance(state, true),
        Action::TrackEnded => {
            if state.repeat == Repeat::One {
                if let Some(track) = state.current().map(str::to_owned) {
                    state.position_ms = 0;
                    return vec![Effect::Play {
                        track,
                        position_ms: 0,
                    }];
                }
            }
            advance(state, false)
        }

        Action::Previous => {
            if state.is_empty() {
                return vec![Effect::Stop];
            }
            // Well into the song: restart it. This is what every player does and what people
            // expect, and it is a seek rather than a reload — reloading would re-resolve a stream
            // that is already playing perfectly well.
            if state.position_ms > PREVIOUS_RESTART_THRESHOLD_MS {
                state.position_ms = 0;
                return vec![Effect::SeekTo(0), Effect::Persist];
            }
            let next_index = match (state.index, state.repeat) {
                (0, Repeat::All) => state.queue.len() - 1,
                // At the start with no wrapping: restart rather than refuse, which is less
                // surprising than a button that does nothing.
                (0, _) => 0,
                (i, _) => i - 1,
            };
            state.index = next_index;
            state.position_ms = 0;
            let track = state.queue[next_index].clone();
            vec![
                Effect::Play {
                    track,
                    position_ms: 0,
                },
                Effect::Persist,
            ]
        }

        Action::Seek(position_ms) => {
            state.position_ms = position_ms;
            vec![Effect::SeekTo(position_ms), Effect::Persist]
        }

        // No `Persist`: this arrives several times a second, and persisting on every one would be a
        // write per tick. Whoever owns the timer decides how often the position is worth keeping.
        Action::Tick(position_ms) => {
            state.position_ms = position_ms;
            Vec::new()
        }

        Action::SetRepeat(repeat) => {
            state.repeat = repeat;
            vec![Effect::Persist]
        }

        Action::SetShuffle { on, order } => {
            state.shuffle = on;
            // An empty order means "keep what you have" — turning shuffle off without supplying the
            // original order should not silently reorder anything.
            if !order.is_empty() {
                let current = state.current().map(str::to_owned);
                state.queue = order;
                // Stay on the same song across the reorder. Losing your place because you pressed
                // shuffle is the bug this line exists to prevent.
                state.index = current
                    .and_then(|track| state.queue.iter().position(|t| *t == track))
                    .unwrap_or(0);
            }
            vec![Effect::Persist]
        }

        Action::Clear => {
            *state = PlayerState {
                repeat: state.repeat,
                shuffle: state.shuffle,
                ..PlayerState::default()
            };
            vec![Effect::Stop, Effect::Persist]
        }
    }
}

/// Moves forward one, honouring repeat. `by_hand` distinguishes a button press from a track ending.
fn advance(state: &mut PlayerState, by_hand: bool) -> Vec<Effect> {
    if state.is_empty() {
        return vec![Effect::Stop];
    }
    let last = state.queue.len() - 1;
    if state.index < last {
        state.index += 1;
    } else {
        match state.repeat {
            Repeat::All => state.index = 0,
            // Repeat-one at the end of the queue, by hand: there is nowhere to go, so stay put and
            // replay rather than stop — the user asked for something to happen.
            Repeat::One if by_hand => {}
            _ => {
                state.position_ms = 0;
                return vec![Effect::Stop, Effect::Persist];
            }
        }
    }
    state.position_ms = 0;
    let track = state.queue[state.index].clone();
    vec![
        Effect::Play {
            track,
            position_ms: 0,
        },
        Effect::Persist,
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn queue_of(n: usize) -> PlayerState {
        PlayerState {
            queue: (0..n).map(|i| format!("t{i}")).collect(),
            ..PlayerState::default()
        }
    }

    fn played(effects: &[Effect]) -> Option<&str> {
        effects.iter().find_map(|e| match e {
            Effect::Play { track, .. } => Some(track.as_str()),
            _ => None,
        })
    }

    #[test]
    fn next_walks_the_queue_and_then_stops() {
        let mut state = queue_of(3);
        assert_eq!(played(&reduce(&mut state, Action::Next)), Some("t1"));
        assert_eq!(played(&reduce(&mut state, Action::Next)), Some("t2"));
        let end = reduce(&mut state, Action::Next);
        assert!(end.contains(&Effect::Stop));
        assert_eq!(played(&end), None);
        // And it stays on the last track rather than falling off it.
        assert_eq!(state.index, 2);
    }

    #[test]
    fn repeat_all_wraps_instead_of_stopping() {
        let mut state = queue_of(3);
        state.index = 2;
        state.repeat = Repeat::All;
        assert_eq!(played(&reduce(&mut state, Action::Next)), Some("t0"));
        assert_eq!(state.index, 0);
    }

    #[test]
    fn repeat_one_repeats_when_a_track_ends_but_not_when_you_press_next() {
        // The distinction that gets implemented wrong: repeat-one means "play this again when it
        // finishes", not "the next button no longer works".
        let mut state = queue_of(3);
        state.repeat = Repeat::One;

        let ended = reduce(&mut state, Action::TrackEnded);
        assert_eq!(played(&ended), Some("t0"));
        assert_eq!(state.index, 0);

        let pressed = reduce(&mut state, Action::Next);
        assert_eq!(played(&pressed), Some("t1"));
        assert_eq!(state.index, 1);
    }

    #[test]
    fn repeat_one_at_the_end_of_the_queue_replays_rather_than_stopping() {
        // Pressing next has to do *something*; stopping the music because you asked for the next
        // song is the worst of the available answers.
        let mut state = queue_of(2);
        state.index = 1;
        state.repeat = Repeat::One;
        assert_eq!(played(&reduce(&mut state, Action::Next)), Some("t1"));
        assert_eq!(state.index, 1);
    }

    #[test]
    fn previous_restarts_the_song_when_you_are_well_into_it() {
        let mut state = queue_of(3);
        state.index = 1;
        state.position_ms = PREVIOUS_RESTART_THRESHOLD_MS + 1;

        let effects = reduce(&mut state, Action::Previous);
        // A seek, not a reload: the stream is already playing and re-resolving it would be work
        // for nothing — and, with an expiring URL, work that can fail.
        assert!(effects.contains(&Effect::SeekTo(0)));
        assert_eq!(played(&effects), None);
        assert_eq!(state.index, 1);
        assert_eq!(state.position_ms, 0);
    }

    #[test]
    fn previous_goes_back_when_you_have_only_just_started() {
        let mut state = queue_of(3);
        state.index = 1;
        state.position_ms = PREVIOUS_RESTART_THRESHOLD_MS;

        assert_eq!(played(&reduce(&mut state, Action::Previous)), Some("t0"));
        assert_eq!(state.index, 0);
    }

    #[test]
    fn previous_at_the_start_restarts_rather_than_doing_nothing() {
        let mut state = queue_of(3);
        assert_eq!(played(&reduce(&mut state, Action::Previous)), Some("t0"));
        assert_eq!(state.index, 0);

        state.repeat = Repeat::All;
        assert_eq!(played(&reduce(&mut state, Action::Previous)), Some("t2"));
        assert_eq!(state.index, 2);
    }

    #[test]
    fn shuffling_keeps_you_on_the_song_you_were_listening_to() {
        // Losing your place because you pressed shuffle is the bug this prevents, and it is one a
        // person notices immediately and a test notices never — unless it is this one.
        let mut state = queue_of(4);
        state.index = 2; // t2

        reduce(
            &mut state,
            Action::SetShuffle {
                on: true,
                order: vec!["t3".into(), "t2".into(), "t0".into(), "t1".into()],
            },
        );
        assert!(state.shuffle);
        assert_eq!(state.current(), Some("t2"));
        assert_eq!(state.index, 1);
    }

    #[test]
    fn turning_shuffle_off_without_an_order_does_not_reorder_anything() {
        let mut state = queue_of(3);
        state.index = 1;
        state.shuffle = true;

        reduce(
            &mut state,
            Action::SetShuffle {
                on: false,
                order: Vec::new(),
            },
        );
        assert!(!state.shuffle);
        assert_eq!(state.queue, vec!["t0", "t1", "t2"]);
        assert_eq!(state.index, 1);
    }

    #[test]
    fn a_tick_does_not_ask_for_a_write() {
        // It arrives several times a second. Persisting on each one would be a disk write per tick,
        // which is how a progress bar becomes a battery complaint.
        let mut state = queue_of(2);
        assert_eq!(reduce(&mut state, Action::Tick(4_000)), Vec::new());
        assert_eq!(state.position_ms, 4_000);
    }

    #[test]
    fn every_action_is_answerable_on_an_empty_queue() {
        // Totality: the caller never has to ask whether a button is safe to press.
        for action in [
            Action::Next,
            Action::Previous,
            Action::TrackEnded,
            Action::Seek(1_000),
            Action::Tick(1_000),
            Action::Clear,
            Action::SetRepeat(Repeat::All),
            Action::SetShuffle {
                on: true,
                order: Vec::new(),
            },
        ] {
            let mut state = PlayerState::default();
            let _ = reduce(&mut state, action);
            assert!(state.current().is_none());
        }
    }

    #[test]
    fn setting_a_queue_past_its_end_starts_at_the_last_track() {
        let mut state = PlayerState::default();
        let effects = reduce(
            &mut state,
            Action::SetQueue {
                tracks: vec!["a".into(), "b".into()],
                start: 99,
            },
        );
        assert_eq!(played(&effects), Some("b"));
        assert_eq!(state.index, 1);
    }

    #[test]
    fn an_empty_queue_stops_rather_than_leaving_the_old_one_playing() {
        let mut state = queue_of(3);
        state.index = 2;
        let effects = reduce(
            &mut state,
            Action::SetQueue {
                tracks: Vec::new(),
                start: 0,
            },
        );
        assert!(effects.contains(&Effect::Stop));
        assert!(state.is_empty());
        assert_eq!(state.index, 0);
    }

    #[test]
    fn repeat_and_shuffle_survive_the_queue_being_replaced() {
        // They are settings, not properties of a particular queue: clearing the queue must not
        // silently turn repeat off.
        let mut state = queue_of(2);
        state.repeat = Repeat::All;
        state.shuffle = true;
        reduce(&mut state, Action::Clear);
        assert_eq!(state.repeat, Repeat::All);
        assert!(state.shuffle);
    }
}
