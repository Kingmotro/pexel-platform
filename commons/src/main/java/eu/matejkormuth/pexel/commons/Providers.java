// @formatter:off
/*
 * Pexel Project - Minecraft minigame server platform. 
 * Copyright (C) 2014 Matej Kormuth <http://www.matejkormuth.eu>
 * 
 * This file is part of Pexel.
 * 
 * Pexel is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * 
 * Pexel is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 */
// @formatter:on
package eu.matejkormuth.pexel.commons;

import java.util.Random;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Class that conitains JSON provider.
 */
public abstract class Providers {
    /**
     * Global network JSON provider.
     */
    public static final Gson               JSON        = new GsonBuilder().setPrettyPrinting()
                                                               .create();
    /**
     * Global random provider.
     */
    public static final Random             RANDOM      = new Random();
    
    /**
     * Global random name provider.
     */
    public static final RandomNameProvider RANDOM_NAME = new RandomNameProvider();
}
