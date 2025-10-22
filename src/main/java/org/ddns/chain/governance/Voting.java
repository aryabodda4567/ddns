package org.ddns.chain.governance;

import org.ddns.net.Message;

import java.util.Map;
import java.util.Set;

public interface Voting {

    public boolean createNominationRequest(int timeLimit);
    public void resolveNominationRequest(Message message, Map<String,String> payload);
    public boolean createCastVoteRequest(boolean response , Nomination nomination);
    public void resolveCastVoteRequest(Map<String,String> payload);
    public void endElection();
    public int getRequiredVotes();
    public int getObtainedVotes();
    public boolean processResult();
    public Set<Nomination> getNominations();
    public void addNomination(Nomination nomination);


}
