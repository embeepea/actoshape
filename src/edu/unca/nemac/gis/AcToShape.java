/*
 * This file is part of AcToShape
 * by Mark Phillips
 * mphillip@unca.edu
 *
 * Copyright (c) 2009  University of North Carolina at Asheville
 * Licensed under the RENCI Open Source Software License v. 1.0.
 * See the file LICENSE.txt for details.
 */

package edu.unca.nemac.gis;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;

import org.geotools.feature.AttributeType;
import org.geotools.feature.AttributeTypeFactory;
import org.geotools.feature.Feature;
import org.geotools.feature.FeatureType;
import org.geotools.feature.FeatureTypeFactory;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.Polygon;

public class AcToShape {

    FeatureType schema;
    GeometryFactory gf;
    Box box = null;
    int geoIndex = 0;
    int ntimesteps;
    /*    boolean noWater = false; */
    Vertex vertices[];
    Triangle triangles[];
    boolean verbose = true;
    boolean debugfields = false;
    int num_triangles_exported = 0;
    boolean clipcoast = false;
    double clipcoast_cliplevel = 0.0;
    boolean interpolate = false;

    public void setInterpolate(boolean interpolate) {
        this.interpolate = interpolate;
    }

    public void setClipcoast(boolean clipcoast) {
        this.clipcoast = clipcoast;
    }

    public void setClipcoastCliplevel(double clipcoast_cliplevel) {
        this.clipcoast_cliplevel = clipcoast_cliplevel;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /*
      public void setNoWater(boolean nowater) {
      this.noWater = nowater;
      }
    */

    public void setBox(double xmin, double ymin, double xmax, double ymax) {
        this.box = new Box(xmin, ymin, xmax, ymax);
    }

    public void setBox(Box box) {
        this.box = box;
    }

    public AcToShape() throws Exception {
        this(false);
    }

    public AcToShape(boolean debugfields) throws Exception {
        this.debugfields = debugfields;
        ArrayList<AttributeType> attTypes = new ArrayList<AttributeType>();
        attTypes.add( AttributeTypeFactory.newAttributeType("geom",        Geometry.class) );
        attTypes.add( AttributeTypeFactory.newAttributeType("timestep",    Integer.class, true, 8) );
        attTypes.add( AttributeTypeFactory.newAttributeType("depth",       Float.class, true, 13) );
        attTypes.add( AttributeTypeFactory.newAttributeType("floodlevel",  Float.class, true, 13) );
        attTypes.add( AttributeTypeFactory.newAttributeType("level63",     Float.class, true, 13) );
        if (debugfields) {
            attTypes.add( AttributeTypeFactory.newAttributeType("tindex",      Integer.class, true,  8) );
            attTypes.add( AttributeTypeFactory.newAttributeType("vindices",    String.class,  true, 32) );
        }
        schema = FeatureTypeFactory.newFeatureType(attTypes.toArray(new AttributeType[0]), "AcToShape");
        gf = new GeometryFactory();
    }

    public Feature triangleToFeature(int geoIndex, Triangle t, int time) throws Exception {
        Coordinate coords[] = null;
        Vertex p, q;
        int j,k;
        int numVerticesBelowClipLevel = 0;

        double avg_depth      = t.depth;
        double avg_floodlevel = t.floodlevel[time];
        double avg_level63    = t.level63[time];

        //        if (clipcoast) {
        for (int i=0; i<3; ++i) {
            if (vertices[t.vertex_indices[i]].depth > clipcoast_cliplevel) { ++numVerticesBelowClipLevel; }
        }
        //        }
        /* if (this.noWater && numVerticesBelowClipLevel==3) { return null; } */
        if (!clipcoast) {
            coords = new Coordinate[4];
            for (int i=0; i<4; ++i) {
                p = vertices[t.vertex_indices[i%3]];
                coords[i] = new Coordinate(p.x, p.y);
            }
        } else {
            switch (numVerticesBelowClipLevel) {

            case 0:
                coords = new Coordinate[4];
                for (int i=0; i<4; ++i) {
                    p = vertices[t.vertex_indices[i%3]];
                    coords[i] = new Coordinate(p.x, p.y);
                }
                break;

            case 1:
                coords = new Coordinate[5];
                k = 0;
                avg_depth      = 0;
                avg_floodlevel = 0;
                avg_level63    = 0;
                for (int i=0; i<3; ++i) {
                    p = vertices[t.vertex_indices[i]];
                    if (p.depth <= clipcoast_cliplevel) {
                        coords[k++] = new Coordinate(p.x, p.y);
                        avg_depth      += p.depth;
                        avg_floodlevel += p.floodlevel[time];
                        avg_level63    += p.level63[time];
                    } else {
                        q = vertices[t.vertex_indices[posMod(i+2,3)]];
                        coords[k++] = WeightedLinearCombniationCoordinate(q, q.depth, p, p.depth, clipcoast_cliplevel);
                        avg_depth      += 0;
                        avg_floodlevel += WeightedLinearCombniationValue(q.floodlevel[time], q.depth, p.floodlevel[time], p.depth, clipcoast_cliplevel);
                        avg_level63    += WeightedLinearCombniationValue(q.level63[time],    q.depth, p.level63[time],    p.depth, clipcoast_cliplevel);

                        q = vertices[t.vertex_indices[posMod(i+1,3)]];
                        coords[k++] = WeightedLinearCombniationCoordinate(p, p.depth, q, q.depth, clipcoast_cliplevel);
                        avg_depth      += 0;
                        avg_floodlevel += WeightedLinearCombniationValue(p.floodlevel[time], p.depth, q.floodlevel[time], q.depth, clipcoast_cliplevel);
                        avg_level63    += WeightedLinearCombniationValue(p.level63[time],    p.depth, q.level63[time],    q.depth, clipcoast_cliplevel);
                    }
                }
                // Note: the above loop populates 4 vertices, even though it only executes 3 times (i=0,1,2),
                // because the inner 'else' clause, which inserts 2 vertices, will be execute exactly once,
                // since numVerticesBelowClipLevel==1.  Now close the polyline (which is a quadrilateral):
                coords[4] = coords[0];
                if (this.interpolate) {
                    avg_depth      /= 4;
                    avg_floodlevel /= 4;
                    avg_level63    /= 4;
                } else {
                    avg_depth      = t.depth;
                    avg_floodlevel = t.floodlevel[time];
                    avg_level63    = t.level63[time];
                }
                break;

            case 2:
                coords = new Coordinate[4];
                // find the one nonpostive vertex; there must be exactly one, since numVerticesBelowClipLevel==2 here:
                int i;
                avg_depth      = 0;
                avg_floodlevel = 0;
                avg_level63    = 0;
                for (i=0; i<3; ++i) {
                    if (vertices[t.vertex_indices[i]].depth <= clipcoast_cliplevel) { break; }
                }
                // now i is the index of the nonpostive vertex
                j = 0;
                p = vertices[t.vertex_indices[i]];
                coords[j++] = new Coordinate(p.x, p.y);
                avg_depth      += p.depth;
                avg_floodlevel += p.floodlevel[time];
                avg_level63    += p.level63[time];

                k = posMod(i+1,3);
                q = vertices[t.vertex_indices[k]];
                Coordinate c = WeightedLinearCombniationCoordinate(p, p.depth, q, q.depth, clipcoast_cliplevel);
                coords[j++] = c;
                avg_depth      += 0;
                avg_floodlevel += WeightedLinearCombniationValue(p.floodlevel[time], p.depth, q.floodlevel[time], q.depth, clipcoast_cliplevel);
                avg_level63    += WeightedLinearCombniationValue(p.level63[time],    p.depth, q.level63[time],    q.depth, clipcoast_cliplevel);

                k = posMod(i+2,3);
                q = vertices[t.vertex_indices[k]];
                c = WeightedLinearCombniationCoordinate(q, q.depth, p, p.depth, clipcoast_cliplevel);
                coords[j++] = c;
                avg_depth      += 0;
                avg_floodlevel += WeightedLinearCombniationValue(q.floodlevel[time], q.depth, p.floodlevel[time], p.depth, clipcoast_cliplevel);
                avg_level63    += WeightedLinearCombniationValue(q.level63[time],    q.depth, p.level63[time],    p.depth, clipcoast_cliplevel);

                coords[3] = coords[0];

                if (this.interpolate) {
                    avg_depth      /= 3;
                    avg_floodlevel /= 3;
                    avg_level63    /= 3;
                } else {
                    avg_depth      = t.depth;
                    avg_floodlevel = t.floodlevel[time];
                    avg_level63    = t.level63[time];
                }
            }
        }

        LinearRing lr = gf.createLinearRing(coords);
        Polygon poly = gf.createPolygon(lr, null);
        ArrayList<Object> objs = new ArrayList<Object>();
        objs.add(poly);
        objs.add(new Integer(time));
        objs.add(new Float(avg_depth));
        objs.add(new Float(avg_floodlevel));
        objs.add(new Float(avg_level63));
        if (debugfields) {
            objs.add(new Integer(t.index));
            objs.add(String.format("%1d,%1d,%1d",
                                   vertices[t.vertex_indices[0]].index,
                                   vertices[t.vertex_indices[1]].index,
                                   vertices[t.vertex_indices[2]].index));
        }
        Feature feature = schema.create(objs.toArray(new Object[0]),
                                        new Integer(geoIndex).toString()
                                        );
        return feature;
    }

    private static int posMod(int i, int n) {
        return i % n;
        /*
          int m = i % n;
          if (m < 0) { return -m; }
          return m;
        */
    }

    private static Coordinate WeightedLinearCombniationCoordinate(Vertex a, double wa, Vertex b, double wb, double wlev) {
        wa -= wlev;
        wb -= wlev;
        double f = wb - wa;
        return new Coordinate( (a.x * wb - b.x * wa) / f,  (a.y * wb - b.y * wa) / f );
    }
    
    private static double WeightedLinearCombniationValue(double a, double wa, double b, double wb, double wlev) {
        wa -= wlev;
        wb -= wlev;
        return (a * wb - b * wa) / (wb - wa) ;
    }
    
    private void exportFeatures(ShapefileExporter se, Triangle t, int timestep)
        throws Exception {
        //              if (box == null || t.lies_within_box(vertices, box)) {
        double floodlevel = t.floodlevel[timestep];
        if (t.depth > 0) {
            // if this triangle is below sea level, force its floodlevel to be 100
            floodlevel = 100;
        }
        //        if (floodlevel > 0 && (!this.noWater || floodlevel < 100)) {
        if (floodlevel > 0) {
            Feature f = triangleToFeature(geoIndex++, t, timestep);
            if (f != null) {
                se.addFeature(f);
                ++num_triangles_exported;
            }
        }
        //}
    }

    
    public void clipToBox() {
        if (box == null) { return; }

        //
        // Identify the triangles and points that lie within the box, populating
        // two int arrays that map their old indices to their new ones.
        //

        int new_vertex_indices[]   = new int[vertices.length];
        // new_vertex_indices[old_index] = new_index;
        //   vertices[old_index] in the old list becomes vertices[new_index] in the new list

        int new_triangle_indices[] = new int[triangles.length];
        // new_triangle_indices[old_index] = new_index;
        //   triangles[old_index] in the old list becomes triangles[new_index] in the new list

        int new_triangle_index = 1;
        int new_vertex_index = 1;
        int old_triangle_index, old_vertex_index;
        for (old_vertex_index=0; old_vertex_index<new_vertex_indices.length; ++old_vertex_index) {
            new_vertex_indices[old_vertex_index] = -1;
        }
        for (old_triangle_index=0; old_triangle_index<new_triangle_indices.length; ++old_triangle_index) {
            new_triangle_indices[old_triangle_index] = -1; 
        }
        Triangle t;
        for (old_triangle_index=1; old_triangle_index<triangles.length; ++old_triangle_index) {
            t = triangles[old_triangle_index];
            if (t.lies_within_box(vertices, box)) {
                new_triangle_indices[old_triangle_index] = new_triangle_index++;
                //System.out.printf("triangle old #%1d becomes new #%1d\n", old_triangle_index, new_triangle_indices[old_triangle_index]); 
                for (int j=0; j<3; ++j) {
                    old_vertex_index = t.vertex_indices[j];
                    if (new_vertex_indices[old_vertex_index] < 0) {
                        new_vertex_indices[old_vertex_index] = new_vertex_index++;
                        //System.out.printf("    vertex old #%1d becomes new #%1d\n", old_vertex_index, new_vertex_indices[old_vertex_index]);                         
                    } else {
                        //System.out.printf("    vertex old #%1d is already new #%1d\n", old_vertex_index, new_vertex_indices[old_vertex_index]);                         
                    }
                }
            }
        }

        //
        // Allocate the new arrays, and populate them
        //
        Vertex new_vertices[] = new Vertex[new_vertex_index];
        //new_vertex_index = 1;
        for (old_vertex_index=1; old_vertex_index<vertices.length; ++old_vertex_index) {
            new_vertex_index = new_vertex_indices[old_vertex_index];
            if (new_vertex_index >= 0) {
                new_vertices[new_vertex_index] = vertices[old_vertex_index];
                //System.out.printf("new_vertices[%1d] <- vertices[%1d] (.index=%1d)\n",
                //new_vertex_index, old_vertex_index, vertices[old_vertex_index].index);
            }
        }
        Triangle new_triangles[] = new Triangle[new_triangle_index];
        new_triangle_index = 1;
        for (old_triangle_index=1; old_triangle_index<triangles.length; ++old_triangle_index) {
            if (new_triangle_indices[old_triangle_index] >= 0) {
                t = triangles[old_triangle_index];
                new_triangles[new_triangle_index++] = new Triangle(t.index,
                                                                   new_vertex_indices[t.vertex_indices[0]],
                                                                   new_vertex_indices[t.vertex_indices[1]],
                                                                   new_vertex_indices[t.vertex_indices[2]]);
                //                System.out.printf("new_triangles[%1d] <- new Triangle(%1d, %1d, %1d, %1d)  [old verts: %1d, %1d, %1d]\n",
                //                              new_triangle_index-1, t.index, 
                //                              new_vertex_indices[t.vertex_indices[0]],
                //                        new_vertex_indices[t.vertex_indices[1]],
                //                        new_vertex_indices[t.vertex_indices[2]],
                //                        new_vertices[new_vertex_indices[t.vertex_indices[0]]].index,
                //                        new_vertices[new_vertex_indices[t.vertex_indices[1]]].index,
                //                        new_vertices[new_vertex_indices[t.vertex_indices[2]]].index
                //                );
            }
        }

        //
        // replace old arrays with new ones
        //
        triangles = new_triangles;
        vertices = new_vertices;

    }

    /*
      subdivide:

      * add 3 extra vertices per triangle
      * replace each triangle with 4 triangles

      length(new vertex array) = length(old vertex array) + 3 * length(old triangle array)
      length(new triangle array) = 4 * length(old triangle array)
    */

    public void subdivide() {
        Vertex   new_vertices[]  = new Vertex[ (vertices.length-1) + 3*triangles.length + 1];
        Triangle new_triangles[] = new Triangle[ 4*(triangles.length-1) + 1 ];
                
        int new_vertex_index;
        for (new_vertex_index=1; new_vertex_index<vertices.length; ++new_vertex_index) {
            new_vertices[new_vertex_index] = vertices[new_vertex_index];
        }

        int new_triangle_index = 0;
        Triangle t;
        Vertex a,b,c,ab,ac,bc;
        int a_index,b_index,c_index,ab_index,ac_index,bc_index;
        for (int old_triangle_index=1; old_triangle_index<triangles.length; ++old_triangle_index) {
            t = triangles[old_triangle_index];
            a = new_vertices[a_index=t.vertex_indices[0]];
            b = new_vertices[b_index=t.vertex_indices[1]];
            c = new_vertices[c_index=t.vertex_indices[2]];
            ab = Vertex.average(a,b);
            ac = Vertex.average(a,c);
            bc = Vertex.average(b,c);
            new_vertices[ab_index=new_vertex_index] = ab; ++new_vertex_index;
            new_vertices[ac_index=new_vertex_index] = ac; ++new_vertex_index;
            new_vertices[bc_index=new_vertex_index] = bc; ++new_vertex_index;
            new_triangles[new_triangle_index++] = new Triangle(t.index, a_index,ab_index,ac_index);
            new_triangles[new_triangle_index++] = new Triangle(t.index, ab_index,bc_index,ac_index);
            new_triangles[new_triangle_index++] = new Triangle(t.index, ab_index,b_index,bc_index);
            new_triangles[new_triangle_index++] = new Triangle(t.index, bc_index,c_index,ac_index);
        }
        vertices  = new_vertices;
        triangles = new_triangles;
    }



    private void output(String format, Object... args) {
        output(this.verbose, format, args);
    }

    private static void output(boolean verbose, String format, Object... args) {
        if (verbose) {
            System.out.printf(format, args);
        }
    }

    private void loadGridFile(String gridFilename) throws Exception {
        BufferedReader br = new BufferedReader(new FileReader(new File(gridFilename)));
        String line = br.readLine(); // skip first line
        // next line is: #triangles #vertices
        line = br.readLine().trim();
        String fields[] = line.split(" +");
        int ntriangles, nvertices;
        ntriangles = Integer.parseInt(fields[0]);
        nvertices = Integer.parseInt(fields[1]);

        vertices = new Vertex[nvertices+1];
        output(" %1d vertices,", nvertices);
        for (int i=1; i<=nvertices; ++i) {
            line = br.readLine().trim();
            fields = line.split(" +");
            vertices[i] = new Vertex(Integer.parseInt(fields[0]),
                                     Double.parseDouble(fields[1]),
                                     Double.parseDouble(fields[2]),
                                     Double.parseDouble(fields[3]));
        }

        triangles = new Triangle[ntriangles+1];
        output(" %1d triangles.", ntriangles);
        for (int i=1; i<=ntriangles; ++i) {
            line = br.readLine().trim();
            fields = line.split(" +");
            triangles[i] = new Triangle(Integer.parseInt(fields[0]),
                                        Integer.parseInt(fields[2]),
                                        Integer.parseInt(fields[3]),
                                        Integer.parseInt(fields[4]));
        }
                                  
        br.close();
    }

    public void load63File(String filename) throws Exception {
        Reader63 r63 = new Reader63(filename);
        String line = r63.readLine(); // skip first line
        // on second line,  1st field is number of time steps, 2nd field is number of vertices
        line = r63.readLine().trim();
        String fields[] = line.split(" +");
        ntimesteps = Integer.parseInt(fields[0]);
        output(" %1d timesteps:", ntimesteps);
        int npts = Integer.parseInt(fields[1]);
        // confirm that number of vertices is
        if (npts != vertices.length-1) {
            throw new Exception(String.format("%s: number of vertices should be %1d\n", filename, vertices.length-1));
        }
        for (int time=0; time<ntimesteps; ++time) {
            output(" %1d", time+1);
            // skip next line
            r63.readLine();
            // read vertices & levels for this timestep:
            for (int i=0; i<npts; ++i) {
                line = r63.readLine().trim();
                fields = line.split(" +");
                int j = Integer.parseInt(fields[0]);
                double wlev = Double.parseDouble(fields[1]);
                if (time==0) {
                    vertices[j].level63 = new double[ntimesteps];
                    vertices[j].floodlevel = new double[ntimesteps];
                }
                vertices[j].level63[time] = wlev;
                vertices[j].floodlevel[time] = vertices[j].level63[time];

                if (vertices[j].depth < 0 ) {
                    // only subtract land surface height/depth when depth<0, i.e. for nodes
                    // that are above MSL
                    vertices[j].floodlevel[time] += vertices[j].depth;
                }

                if (vertices[j].floodlevel[time] < 0) {
                    vertices[j].floodlevel[time] = 0;
                }
            }
        }
        r63.close();
    }

    public void compute() {
        for (Triangle t : triangles) {
            if (t != null) {
                t.compute(this.vertices, this.ntimesteps);
            }
        }
    }


    public void writeShp(String shapefilename, int timestep) throws Exception {
        ShapefileExporter se = new ShapefileExporter(shapefilename);
        int percentdone = 10;
        this.geoIndex = 0;
        this.num_triangles_exported = 0;
        for (int i=1; i<triangles.length; ++i) {
            exportFeatures(se, triangles[i], timestep);
            int pdone = (int)(Math.round(100.0 * i/triangles.length));
            if (pdone >= percentdone) {
                output("%1d%%", pdone);
                if (pdone < 100) {
                    output("..");
                } else {
                    //output("\n");
                }
                percentdone += 10;
            }
        }
        se.close();
    }

    public static void main(String args[]) throws Exception {

        int i = 0;
        Box box = null;
        int timestep = -1;
        /* boolean nowater = false; */
        boolean verbose = true;
        boolean debugfields = false;
        int subdivide = 0;
        boolean clipcoast = false;
        double clipcoast_cliplevel = 0.0;
        boolean interpolate = false;

        while (i<args.length && args[i].startsWith("-")) {
            if (args[i].equals("--box") || args[i].equals("-b")) {
                ++i;
                box = new Box(Double.parseDouble(args[i++]), Double.parseDouble(args[i++]),
                              Double.parseDouble(args[i++]), Double.parseDouble(args[i++]));
            } else if (args[i].equals("--timestep") || args[i].equals("-t")) {
                ++i;
                timestep = Integer.parseInt(args[i++]) - 1;
            } else if (args[i].equals("--subdivide") || args[i].equals("-s")) {
                ++i;
                subdivide = Integer.parseInt(args[i++]);
            } else if (args[i].equals("--nowater") || args[i].equals("-W")) {
                /*
                  ++i;
                  nowater = true;
                */
                System.err.printf("Warning: --nowater (-W) option ignored because no longer supported; use --clipcoast instead\n");
            } else if (args[i].equals("--quiet") || args[i].equals("-q")) {
                ++i;
                verbose = false;
            } else if (args[i].equals("--debugfields") || args[i].equals("-g")) {
                ++i;
                debugfields = true;
            } else if (args[i].equals("--interpolate") || args[i].equals("-i")) {
                ++i;
                interpolate = true;
            } else if (args[i].equals("--clipCoast") || args[i].equals("--clipcoast") || args[i].equals("-c")) {
                ++i;
                clipcoast = true;
                if (i<args.length) {
                    try {
                        double z = Double.parseDouble(args[i]);
                        clipcoast_cliplevel = z;
                        ++i;
                    } catch (Exception e) {
                        // just ignore the exception; if we get here, it means the next
                        // arg isn't a number, so just proceed the argument processing
                    }
                }
            } else {
                System.out.printf("unrecognized argument: %s\n", args[i]);
                System.exit(0);
            }
        }

        if (args.length - i != 3) {
            System.out.printf("usage: actoshape [OPTIONS] GRIDFILE ETSFILE SHAPEFILE\n");
            System.exit(0);
        }

        String grdfile = args[i++];
        String file63  = args[i++];
        String shpfile = args[i++];

        if (!(new File(grdfile)).exists()) {
            System.out.printf("Can't read grid file '%s'\n", grdfile);
            System.exit(0);
        }

        if (!(new File(file63)).exists()) {
            System.out.printf("Can't read ETS file '%s'\n", file63);
            System.exit(0);
        }

        AcToShape tm = new AcToShape(debugfields);

        tm.setVerbose(verbose);
        tm.setClipcoast(clipcoast);
        tm.setInterpolate(interpolate);
        if (clipcoast) {
            tm.setClipcoastCliplevel(clipcoast_cliplevel);
        }

        if (box != null) {
            tm.setBox(box);
        }
        /*
          if (nowater) {
          tm.setNoWater(nowater);
          }
        */

        output(verbose, "loading grid file %s:", grdfile);
        tm.loadGridFile(grdfile);
        output(verbose, " done.\n");
        output(verbose, "loading .63 file %s:", file63);
        tm.load63File(file63);
        output(verbose, " done.\n");

        if (box != null) {
            output(verbose, "clipping to specified box ...");
            tm.clipToBox();
            output(verbose, " new mesh has %1d vertices, %1d triangles.\n", tm.vertices.length-1, tm.triangles.length-1);
        }

        if (subdivide > 0) {
            output(verbose, "subdividing:");
            for (int j=0; j<subdivide; ++j) {
                output(verbose, " %1d", j+1);
                tm.subdivide();
            }
            output(verbose, " done.\n");
        }

        output(verbose, "computing water levels for each triangle...");
        tm.compute();
        output(verbose, " done.\n");

        if (timestep >= 0) {
            // a time was explicitly specified, so write a single shapefile for that timestep
            output(verbose, "writing shapefile %s for time step %1d: ", shpfile, timestep+1);
            tm.writeShp(shpfile, timestep);
            output(verbose, " [%1d triangles]\n", tm.num_triangles_exported);
        } else {
            // No timestep was specified, so write a series of shapefiles, one for each timestep in the simulation.
            if (tm.ntimesteps == 1) {
                // If there is only one timestep in the simulation, use the shapefile name given on the command line directly
                output(verbose, "writing shapefile %s: ", shpfile);
                tm.writeShp(shpfile, 0);
                output(verbose, " [%1d triangles]\n", tm.num_triangles_exported);
            } else {
                // Otherwise, construct a series of filenames using the given name as base.
                String filenameFormat = new String(shpfile);
                filenameFormat = filenameFormat.replaceAll("\\.shp$", "").replaceAll("\\.SHP$", "") + "-step-%05d.shp";
                output(verbose, "writing shapefiles for %1d timesteps:\n", tm.ntimesteps);
                for (timestep=0; timestep<tm.ntimesteps; ++timestep) {
                    String filename = String.format(filenameFormat, timestep+1);
                    output(verbose, "  %s: ", filename);
                    tm.writeShp(filename, timestep);
                    output(verbose, " [%1d triangles]\n", tm.num_triangles_exported);
                }
            }
        }

    }

}
