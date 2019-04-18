package sgdk.rescomp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sgdk.rescomp.processor.BinProcessor;
import sgdk.rescomp.processor.BitmapProcessor;
import sgdk.rescomp.processor.ImageProcessor;
import sgdk.rescomp.processor.PaletteProcessor;
import sgdk.rescomp.processor.SpriteProcessor;
import sgdk.rescomp.processor.TilemapProcessor;
import sgdk.rescomp.processor.TilesetProcessor;
import sgdk.rescomp.processor.WavProcessor;
import sgdk.rescomp.processor.XgmProcessor;
import sgdk.rescomp.resource.Bin;
import sgdk.rescomp.resource.Bitmap;
import sgdk.rescomp.resource.Image;
import sgdk.rescomp.resource.Palette;
import sgdk.rescomp.resource.Sprite;
import sgdk.rescomp.resource.Tilemap;
import sgdk.rescomp.resource.Tileset;
import sgdk.rescomp.resource.internal.Collision;
import sgdk.rescomp.resource.internal.SpriteAnimation;
import sgdk.rescomp.resource.internal.SpriteFrame;
import sgdk.rescomp.resource.internal.SpriteFrameInfo;
import sgdk.rescomp.resource.internal.VDPSprite;
import sgdk.tool.FileUtil;
import sgdk.tool.StringUtil;

public class Compiler
{
    // private final static String REGEX_LETTERS = "[a-zA-Z]";
    // private final static String REGEX_ID = "\\b([A-Za-z][A-Za-z0-9_]*)\\b";

    private final static List<Processor> resourceProcessors = new ArrayList<>();

    static
    {
        resourceProcessors.add(new BinProcessor());
        resourceProcessors.add(new PaletteProcessor());
        resourceProcessors.add(new BitmapProcessor());
        resourceProcessors.add(new TilesetProcessor());
        resourceProcessors.add(new TilemapProcessor());
        resourceProcessors.add(new ImageProcessor());
        resourceProcessors.add(new SpriteProcessor());
        resourceProcessors.add(new WavProcessor());
        resourceProcessors.add(new XgmProcessor());
    }

    // shared directory informations
    public static String currentDir;
    public static String resDir;

    // map storing all resources (same object for key and value as we want fast 'contains' check)
    public static Map<Resource, Resource> resources = new HashMap<>();
    // list to preserve order
    public static List<Resource> resourcesList = new ArrayList<>();

    public static boolean compile(String fileName, String fileNameOut, boolean header)
    {
        //currentDir = new File("").getAbsolutePath();
        currentDir = FileUtil.getApplicationDirectory();
        // get input file directory
        resDir = FileUtil.getDirectory(fileName);

        List<String> lines = null;

        try
        {
            // get all lines from resource file
            lines = Files.readAllLines(Paths.get(fileName), Charset.defaultCharset());
        }
        catch (IOException e)
        {
            System.err.println("Couldn't open input file " + fileName + ":");
            System.err.println(e.getMessage());
            return false;
        }

        // process input resource file line by line
        for (String line : lines)
        {
            // ignore empty line
            if (StringUtil.isEmpty(line))
                continue;
            // ignore comment
            if (line.startsWith("//") || line.startsWith("#"))
                continue;

            // execute and get resource
            final Resource resource = execute(line);

            // can't get resource ? --> error happened, stop here..
            if (resource == null)
                return false;

            // store resource
            addResource(resource);
        }

        // define output file printer
        PrintWriter outS = null;
        PrintWriter outH = null;

        try
        {
            outS = new PrintWriter(FileUtil.setExtension(fileNameOut, ".s"));
            outH = new PrintWriter(FileUtil.setExtension(fileNameOut, ".h"));
        }
        catch (FileNotFoundException e)
        {
            System.err.println("Couldn't create output file:");
            System.err.println(e.getMessage());
            return false;
        }

        try
        {
            final ByteArrayOutputStream outB = new ByteArrayOutputStream();

            // Read Only Data section
            outS.write(".section .rodata\n\n");
            // outS.write(".text\n\n");

            // build header name from resource parent folder name
            String headerName = FileUtil.getFileName(FileUtil.getDirectory(resDir, false), false);
            if (StringUtil.isEmpty(headerName))
                headerName = "RES";
            headerName += "_" + FileUtil.getFileName(fileNameOut, false);
            headerName = headerName.toUpperCase();

            outH.write("#ifndef _" + headerName + "_H_\n");
            outH.write("#define _" + headerName + "_H_\n\n");

            // export resources by type for better compression (binary data first)
            exportResources(getResources(Bin.class), outB, outS, outH);
            exportResources(getResources(VDPSprite.class), outB, outS, outH);
            exportResources(getResources(Collision.class), outB, outS, outH);

            // then we can export other type of resource (no compression)
            exportResources(getResources(Palette.class), outB, outS, outH);
            exportResources(getResources(Tileset.class), outB, outS, outH);
            exportResources(getResources(Tilemap.class), outB, outS, outH);
            exportResources(getResources(SpriteFrameInfo.class), outB, outS, outH);
            exportResources(getResources(SpriteFrame.class), outB, outS, outH);
            exportResources(getResources(SpriteAnimation.class), outB, outS, outH);
            exportResources(getResources(Sprite.class), outB, outS, outH);
            exportResources(getResources(Image.class), outB, outS, outH);
            exportResources(getResources(Bitmap.class), outB, outS, outH);

            outH.write("\n");
            outH.write("#endif // _" + headerName + "_H_\n");
        }
        catch (Throwable t)
        {
            System.err.println(t.getMessage());
            return false;
        }
        finally
        {
            outS.flush();
            outH.flush();
            outS.close();
            outH.close();
        }

        // remove unwanted header file if asked
        if (!header)
            FileUtil.delete(FileUtil.setExtension(fileNameOut, ".h"), false);

        return true;
    }

    private static List<Resource> getResources(Class<? extends Resource> resourceType)
    {
        final List<Resource> result = new ArrayList<>();

        for (Resource resource : resourcesList)
            if (resourceType.isInstance(resource))
                result.add(resource);

        return result;
    }

    private static void exportResources(List<Resource> resources, ByteArrayOutputStream outB, PrintWriter outS,
            PrintWriter outH) throws IOException
    {
        for (Resource res : resources)
            exportResource(res, outB, outS, outH);
    }

    private static void exportResource(Resource resource, ByteArrayOutputStream outB, PrintWriter outS,
            PrintWriter outH) throws IOException
    {
        resource.out(outB, outS, outH);
    }

    public static Resource addResource(Resource resource, boolean internal)
    {
        // internal resource --> not global
        if (internal)
            resource.global = false;

        // check if we already have this resource
        final Resource result = resources.get(resource);

        // return it if already exists
        if (result != null)
        {
            // System.out.println("Duplicated resource found: " + resource.id + " = " + result.id);
            return result;
        }

        // add resource
        resources.put(resource, resource);
        resourcesList.add(resource);

        return resource;
    }

    public static Resource addResource(Resource resource)
    {
        return addResource(resource, false);
    }

    private static Resource execute(String input)
    {
        try
        {
            // regex pattern to properly separate quoted string
            final String pattern = " +(?=(([^\"]*\"){2})*[^\"]*$)";
            // split on space character and preserve quoted parts
            final String[] fields = input.split(pattern);
            // remove quotes
            for (int f = 0; f < fields.length; f++)
                fields[f] = fields[f].replaceAll("\"", "");

            // get resource type
            final String type = fields[0];
            // get resource processor for this type
            final Processor processor = getResourceProcessor(type);

            // can't find matching processor ? --> invalid resource type
            if (processor == null)
            {
                System.err.println("Error: unknown resource type '" + type + "'");
                System.out.println("Accepted resource types are:");

                for (Processor rp : resourceProcessors)
                    System.out.println("  " + rp.getId());

                return null;
            }

            System.out.println();
            System.out.println("Resource: " + input);
            System.out.println("--> executing plugin " + type + "...");

            return processor.execute(fields);
        }
        catch (IOException e)
        {
            System.err.println(e.getMessage());
            System.err.println("Error: cannot compile resource '" + input + "'");
            e.printStackTrace();
            return null;
        }
        catch (IllegalArgumentException e)
        {
            System.err.println(e.getMessage());
            System.err.println("Error: cannot compile resource '" + input + "'");
            e.printStackTrace();
            return null;
        }
    }

    private static Processor getResourceProcessor(String resourceType)
    {
        for (Processor rp : resourceProcessors)
            if (resourceType.equalsIgnoreCase(rp.getId()))
                return rp;

        return null;
    }
}
