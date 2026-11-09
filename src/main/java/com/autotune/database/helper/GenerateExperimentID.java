package com.autotune.database.helper;

import java.util.Objects;

public class GenerateExperimentID {
    private String experiment_id;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GenerateExperimentID)) return false;
        GenerateExperimentID that = (GenerateExperimentID) o;
        return Objects.equals(experiment_id, that.experiment_id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(experiment_id);
    }
}
