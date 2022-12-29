package me.itsmcb.voyage.api;

import java.util.List;

public class VoyageWorldGenerator {

    public enum Origin {

        PLUGIN("plugin"),
        MOJANG("mojang");

        private final String id;

        private Origin(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    private String name;

    private String[] authors;

    private Origin origin;

    public VoyageWorldGenerator(String name, String[] authors, Origin origin) {
        this.name = name;
        this.authors = authors;
        this.origin = origin;
    }

    public VoyageWorldGenerator(String name, List<String> authors, Origin origin) {
        this.name = name;
        this.authors = authors.toArray(new String[0]);
        this.origin = origin;
    }

    public String getName() {
        return name;
    }

    public String[] getAuthors() {
        return authors;
    }

    public String getAuthorsFormatted() {
        return String.join(", ",getAuthors());
    }

    public Origin getOrigin() {
        return origin;
    }
}
