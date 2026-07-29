package linmumua.doudizhu.storage;

import java.util.List;

public record PlayerHistoryEntry(
    long matchId,
    MatchRecord match,
    List<MatchParticipantRecord> participants,
    MatchParticipantRecord self
) {
}
