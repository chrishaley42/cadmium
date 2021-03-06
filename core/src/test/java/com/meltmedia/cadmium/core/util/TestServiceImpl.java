/**
 *    Copyright 2012 meltmedia
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.meltmedia.cadmium.core.util;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.google.inject.Singleton;

@Singleton
public class TestServiceImpl extends AbstractTestService {
    
  @PostConstruct
  void alreadyConstructed() {
  }
  
  @PreDestroy
  void destroying() {
  }

  @Override
  @PostConstruct
  public void doConstruct() {
    super.doConstruct();
  }

  @Override
  @PreDestroy
  public void doDestroy() {
    super.doDestroy();
  }

}
