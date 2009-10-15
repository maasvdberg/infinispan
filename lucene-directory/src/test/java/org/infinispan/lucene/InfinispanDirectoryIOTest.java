/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.lucene;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.infinispan.Cache;
import org.testng.annotations.Test;

/**
 * @author Lukasz Moren
 * @author Sanne Grinovero
 */
@Test(groups = "functional", testName = "lucene.InfinispanDirectoryIOTest")
public class InfinispanDirectoryIOTest {

   public void testReadChunks() throws Exception {
      final int BUFFER_SIZE = 64;

      Cache<CacheKey, Object> cache = CacheTestSupport.createTestCacheManager().getCache();
      InfinispanDirectory dir = new InfinispanDirectory(cache, "index", BUFFER_SIZE);

      // create file headers
      FileMetadata file1 = new FileMetadata();
      CacheKey key1 = new FileCacheKey("index", "Hello.txt");
      cache.put(key1, file1);

      FileMetadata file2 = new FileMetadata();
      CacheKey key2 = new FileCacheKey("index", "World.txt");
      cache.put(key2, file2);

      // byte array for Hello.txt
      String helloText = "Hello world.  This is some text.";
      cache.put(new ChunkCacheKey("index", "Hello.txt", 0), helloText.getBytes());

      // byte array for World.txt - should be in at least 2 chunks.
      String worldText = "This String should contain more than sixty four characters but less than one hundred and twenty eight.";

      byte[] buf = new byte[BUFFER_SIZE];
      System.arraycopy(worldText.getBytes(), 0, buf, 0, BUFFER_SIZE);
      cache.put(new ChunkCacheKey("index", "World.txt", 0), buf);

      String part1 = new String(buf);
      buf = new byte[BUFFER_SIZE];
      System.arraycopy(worldText.getBytes(), BUFFER_SIZE, buf, 0, worldText.length() - BUFFER_SIZE);
      cache.put(new ChunkCacheKey("index", "World.txt", 1), buf);
      String part2 = new String(buf);

      // make sure the generated bytes do add up!
      assert worldText.equals( part1 + part2.trim() );

      file1.setSize(helloText.length());
      file2.setSize(worldText.length());

      Set<String> s = new HashSet<String>();
      s.add("Hello.txt");
      s.add("World.txt");
      Set other = new HashSet(Arrays.asList(dir.list()));

      // ok, file listing works.
      assert s.equals(other);

      IndexInput ii = dir.openInput("Hello.txt");

      assert ii.length() == helloText.length();

      ByteArrayOutputStream baos = new ByteArrayOutputStream();

      for (int i = 0; i < ii.length(); i++) {
         baos.write(ii.readByte());
      }

      assert new String(baos.toByteArray()).equals(helloText);

      ii = dir.openInput("World.txt");

      assert ii.length() == worldText.length();

      baos = new ByteArrayOutputStream();

      for (int i = 0; i < ii.length(); i++) {
         baos.write(ii.readByte());
      }

      assert new String(baos.toByteArray()).equals(worldText);

      // now with buffered reading

      ii = dir.openInput("Hello.txt");

      assert ii.length() == helloText.length();

      baos = new ByteArrayOutputStream();

      long toRead = ii.length();
      while (toRead > 0) {
         buf = new byte[19]; // suitably arbitrary
         int bytesRead = (int) Math.min(toRead, 19);
         ii.readBytes(buf, 0, bytesRead);
         toRead = toRead - bytesRead;
         baos.write(buf, 0, bytesRead);
      }

      assert new String(baos.toByteArray()).equals(helloText);

      ii = dir.openInput("World.txt");

      assert ii.length() == worldText.length();

      baos = new ByteArrayOutputStream();

      toRead = ii.length();
      while (toRead > 0) {
         buf = new byte[19]; // suitably arbitrary
         int bytesRead = (int) Math.min(toRead, 19);
         ii.readBytes(buf, 0, bytesRead);
         toRead = toRead - bytesRead;
         baos.write(buf, 0, bytesRead);
      }

      assert new String(baos.toByteArray()).equals(worldText);

      dir.deleteFile("Hello.txt");
      assert null == cache.get(new FileCacheKey("index", "Hello.txt"));
      assert null == cache.get(new ChunkCacheKey("index", "Hello.txt", 0));

      Object ob1 = cache.get(new FileCacheKey("index", "World.txt"));
      Object ob2 = cache.get(new ChunkCacheKey("index", "World.txt", 0));
      Object ob3 = cache.get(new ChunkCacheKey("index", "World.txt", 1));

      dir.renameFile("World.txt", "HelloWorld.txt");
      assert null == cache.get(new FileCacheKey("index", "Hello.txt"));
      assert null == cache.get(new ChunkCacheKey("index", "Hello.txt", 0));
      assert null == cache.get(new ChunkCacheKey("index", "Hello.txt", 1));

      assert cache.get(new FileCacheKey("index", "HelloWorld.txt")).equals(ob1);
      assert cache.get(new ChunkCacheKey("index", "HelloWorld.txt", 0)).equals(ob2);
      assert cache.get(new ChunkCacheKey("index", "HelloWorld.txt", 1)).equals(ob3);

      // test that contents survive a move
      ii = dir.openInput("HelloWorld.txt");

      assert ii.length() == worldText.length();

      baos = new ByteArrayOutputStream();

      toRead = ii.length();
      while (toRead > 0) {
         buf = new byte[19]; // suitably arbitrary
         int bytesRead = (int) Math.min(toRead, 19);
         ii.readBytes(buf, 0, bytesRead);
         toRead = toRead - bytesRead;
         baos.write(buf, 0, bytesRead);
      }

      assert new String(baos.toByteArray()).equals(worldText);

      cache.getCacheManager().stop();
      dir.close();

   }

   public void testWriteChunks() throws Exception {
      final int BUFFER_SIZE = 64;

      Cache<CacheKey, Object> cache = CacheTestSupport.createTestCacheManager().getCache();
      InfinispanDirectory dir = new InfinispanDirectory(cache, "index", BUFFER_SIZE);

      IndexOutput io = dir.createOutput("MyNewFile.txt");

      io.writeByte((byte) 66);
      io.writeByte((byte) 69);

      io.close();

      assert dir.fileExists("MyNewFile.txt");
      assert null!=cache.get(new ChunkCacheKey("index", "MyNewFile.txt", 0));

      // test contents by reading:
      byte[] buf = new byte[9];
      IndexInput ii = dir.openInput("MyNewFile.txt");
      ii.readBytes(buf, 0, (int) ii.length());

      assert new String(new byte[] { 66, 69 }).equals(new String(buf).trim());

      String testText = "This is some rubbish again that will span more than one chunk - one hopes.  Who knows, maybe even three or four chunks.";
      io.seek(0);
      io.writeBytes(testText.getBytes(), 0, testText.length());
      io.close();
      // now compare.
      byte[] chunk1 = (byte[]) cache.get(new ChunkCacheKey("index", "MyNewFile.txt", 0));
      byte[] chunk2 = (byte[]) cache.get(new ChunkCacheKey("index", "MyNewFile.txt", 1));
      assert null!=chunk1;
      assert null!=chunk2;

      assert testText.equals(new String(chunk1) + new String(chunk2).trim());

      cache.getCacheManager().stop();
      dir.close();
   }

   public void testWriteChunksDefaultChunks() throws Exception {
      Cache<CacheKey, Object> cache = CacheTestSupport.createTestCacheManager().getCache();
      InfinispanDirectory dir = new InfinispanDirectory(cache, "index");

      String testText = "This is some rubbish";
      byte[] testTextAsBytes = testText.getBytes();

      IndexOutput io = dir.createOutput("MyNewFile.txt");

      io.writeByte((byte) 1);
      io.writeByte((byte) 2);
      io.writeByte((byte) 3);
      io.writeBytes(testTextAsBytes, testTextAsBytes.length);
      io.close();

      assert null!=cache.get(new FileCacheKey("index", "MyNewFile.txt"));
      assert null!=cache.get(new ChunkCacheKey("index", "MyNewFile.txt", 0));

      // test contents by reading:
      IndexInput ii = dir.openInput("MyNewFile.txt");
      assert ii.readByte()== 1;
      assert ii.readByte()== 2;
      assert ii.readByte()== 3;
      byte[] buf = new byte[32];

      ii.readBytes(buf, 0, testTextAsBytes.length);

      assert testText.equals(new String(buf).trim());

      cache.getCacheManager().stop();
      dir.close();
   }
}
