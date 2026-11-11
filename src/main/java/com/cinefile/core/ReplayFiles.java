package com.cinefile.core;

import java.io.IOException;
import java.util.List;

/** Read access to the entries of a Flashback replay, whether zip or folder. */
public interface ReplayFiles {
    byte[] read(String name) throws IOException;

    List<String> list() throws IOException;
}
