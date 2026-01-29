package org.ddns.web.services.election;

import org.ddns.constants.ElectionType;

class CreateElectionRequest {
    public String password;
    public String nodeName;
    public int timeMinutes;
    public String description;
    public int electionType;
}
